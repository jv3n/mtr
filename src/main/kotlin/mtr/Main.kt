package mtr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.floor

/**
 * Orchestration / autonomous paper-trading loop.
 *
 * Flow: scanner picks the universe → subscribe to Massive quotes → on each quote,
 * manage an open short (stop/TP) or evaluate a new short (ETB + guardrails). Trades
 * and periodic P&L go through the TradeJournal (#1).
 *
 * Guardrails (#2): daily-loss limit or a manual `KILL` file trip the kill switch →
 * flatten everything, alert, and stop. Order/API calls are isolated so one failure
 * can't crash the loop; positions are reconciled against the broker to catch phantoms.
 *
 * ⚠️ On the free Massive tier (delayed) live signals are sparse; a paid real-time plan
 * is required for a meaningful return.
 */

private data class OpenShort(
    val entryPrice: Double,
    val shares: Int,
    val entryMs: Long,
)

private val KILL_FILE: Path = Path.of("KILL")

private fun log(msg: String) = println("${Instant.now()} $msg")

fun main() =
    kotlinx.coroutines.runBlocking {
        val creds = TradeZeroCredentials.fromEnv()
        if (creds.environment.isLive) log("WARNING: LIVE ENVIRONMENT — real money.")

        val params = StrategyParams()
        val risk = RiskManager(RiskLimits())
        val journal = TradeJournal()
        val notifier = notifierFromEnv()

        // Scheduled-window auto-close: flatten and stop at the end of the trading session
        // (e.g. SESSION_END=15:00, SESSION_TZ=America/New_York). Unset = run until killed.
        val sessionEnd = envOrNull("SESSION_END")?.let { LocalTime.parse(it) }
        val sessionZone = envOrNull("SESSION_TZ")?.let { ZoneId.of(it) }

        val tz = TradeZeroConnector(creds)
        val market = MassiveProvider.fromEnv()
        val account = tz.accountId()
        log("Account: $account (${creds.environment})")

        // Autonomous universe: the scanner picks the day's tickers itself.
        val watchlist = resolveUniverse(Scanner(market))
        val tickers = watchlist.map { it.ticker }
        log("Universe: $tickers")

        val states = tickers.associateWith { TickerState(it) }
        val openShorts = mutableMapOf<String, OpenShort>()
        val haltFlagged = mutableSetOf<String>()

        notifier.send("mtr started · $account (${creds.environment}) · universe=$tickers")
        try {
            coroutineScope {
                val scopeJob = coroutineContext.job

                // Kill-switch watch (fast): manual KILL file or a risk halt → flatten all + stop.
                launch {
                    while (isActive) {
                        if (sessionEnd != null &&
                            sessionZone != null &&
                            !risk.halted &&
                            !ZonedDateTime.now(sessionZone).toLocalTime().isBefore(sessionEnd)
                        ) {
                            log("session end ($sessionEnd ${sessionZone.id}) reached — flattening and stopping")
                            risk.kill()
                        }
                        if (Files.exists(KILL_FILE)) {
                            log("KILL file detected — tripping the kill switch")
                            risk.kill()
                        }
                        if (risk.halted) {
                            val n = flattenAll(tz, account, states, openShorts, journal, risk)
                            notifier.send("🛑 KILL SWITCH — flattened $n position(s); stopping. ${journal.summary().format()}")
                            scopeJob.cancel()
                            break
                        }
                        delay(5_000)
                    }
                }

                // P&L snapshot + broker reconciliation (slow).
                launch {
                    while (isActive) {
                        runCatching {
                            val pnl = tz.getPnl(account)
                            val accountValue = pnl["accountValue"]?.jsonPrimitive?.doubleOrNull
                            val dayPnl = pnl["dayPnl"]?.jsonPrimitive?.doubleOrNull
                            val exposure = pnl["exposure"]?.jsonPrimitive?.doubleOrNull
                            journal.recordAccountPnl(accountValue, dayPnl, exposure)
                            log("PNL accountValue=$accountValue dayPnl=$dayPnl exposure=$exposure | ${journal.summary().format()}")
                            reconcile(tz, account, openShorts, notifier)
                            checkHalts(states, openShorts, params, notifier, haltFlagged, System.currentTimeMillis())
                        }.onFailure { log("periodic check failed: ${it.message}") }
                        delay(60_000)
                    }
                }

                // Resilient market-data loop (auto-reconnect for 24/7).
                var streamFailures = 0
                while (isActive) {
                    runCatching {
                        market.streamQuotes(tickers).collect { q ->
                            val state = states[q.ticker] ?: return@collect
                            updateState(state, q, System.currentTimeMillis())
                            onQuote(state, tz, risk, account, params, openShorts, journal)
                        }
                    }.onFailure {
                        streamFailures++
                        log("stream error: ${it.message} — reconnecting in 5s")
                        if (streamFailures == 3) notifier.send("⚠️ market stream unstable ($streamFailures reconnects)")
                    }
                    delay(5_000)
                }
            }
        } catch (e: CancellationException) {
            log("shutting down (${e.message})")
        } finally {
            tz.close()
            market.close()
            log("FINAL ${journal.summary().format()}")
            notifier.send("mtr stopped. ${journal.summary().format()}")
            notifier.close()
        }
    }

private fun updateState(
    s: TickerState,
    q: Quote,
    nowMs: Long,
) {
    // Record the VWAP side from the PREVIOUS tick before overwriting (for cross detection).
    s.wasAboveVwap = s.vwap > 0.0 && s.lastPrice >= s.vwap
    s.lastUpdateMs = nowMs
    s.lastPrice = q.price
    s.vwap = q.vwap
    if (q.dayOpen > 0) s.dayOpen = q.dayOpen
    s.dayHigh = maxOf(s.dayHigh, q.dayHigh)
    if (s.dayOpen > 0) s.pctChange = (q.price - s.dayOpen) / s.dayOpen
}

private suspend fun onQuote(
    state: TickerState,
    tz: TradeZeroConnector,
    risk: RiskManager,
    account: String,
    params: StrategyParams,
    openShorts: MutableMap<String, OpenShort>,
    journal: TradeJournal,
) {
    val ticker = state.ticker
    val open = openShorts[ticker]

    // 1. Manage an existing short: stop-loss / take-profit / time stop.
    if (open != null) {
        val heldSeconds = (System.currentTimeMillis() - open.entryMs) / 1000
        val exit = shouldExit(open.entryPrice, state.lastPrice, heldSeconds, params)
        if (exit != ExitReason.NONE) {
            val result =
                runCatching { tz.flatten(account, ticker) }
                    .onFailure { log("flatten $ticker failed: ${it.message}") }
                    .getOrNull()
            if (result?.accepted == true) {
                val trade = journal.recordExit(ticker, state.lastPrice, exit.name)
                val realized = trade?.realizedPnlUsd ?: realizedPnl(TradeSide.SHORT, open.shares, open.entryPrice, state.lastPrice)
                risk.registerRealizedPnl(realized)
                risk.closePosition(ticker)
                risk.recordDayTrade()
                openShorts.remove(ticker)
                log("EXIT $ticker ($exit) @ ${state.lastPrice} entry=${open.entryPrice} pnl=%.2f".format(realized))
                if (risk.dayTrades == 4) log("PDT WARNING: 4 day trades today — live trading needs >= \$25k margin equity")
            }
        }
        return
    }

    // 2. No position: look for a new short entry.
    if (risk.halted) return
    val signal = evaluate(state, params)
    if (signal.type != SignalType.SHORT) return

    if (state.lastPrice <= 0) return
    val shares = floor(params.perTradeUsd / state.lastPrice).toInt()
    if (shares < 1) return
    val notional = shares * state.lastPrice

    // Guardrails.
    val (ok, reason) = risk.canOpenShort(ticker, notional)
    if (!ok) {
        log("Short rejected for $ticker: $reason")
        return
    }

    // Paper accepts a short with no locate at all, so the borrow check is pointless there.
    // Live needs a secured locate for hard-to-borrow names, and that flow is not usable yet
    // (locates are live-only, #4) -> keep refusing HTB names on live.
    if (tz.environment.requiresLocateForHardToBorrow) {
        // On an API failure this reads as hard-to-borrow, which is the safe side.
        val etb = runCatching { tz.isEasyToBorrow(account, ticker) }.getOrDefault(false)
        if (!etb) {
            log("Short skipped for $ticker: hard-to-borrow and the locate flow is not wired yet (#4)")
            return
        }
    }

    val result =
        runCatching { tz.submitShort(account, ticker, shares) }
            .onFailure { log("submitShort $ticker failed: ${it.message}") }
            .getOrNull()
    if (result?.accepted == true) {
        openShorts[ticker] = OpenShort(entryPrice = state.lastPrice, shares = shares, entryMs = System.currentTimeMillis())
        risk.registerFill(ticker, notional)
        journal.recordEntry(ticker, TradeSide.SHORT, shares, state.lastPrice, signal.reason)
        log("ENTER SHORT $ticker x$shares @ ${state.lastPrice} (${signal.reason})")
    }
}

/** Cover every tracked short (kill switch). Returns the number of positions closed. */
private suspend fun flattenAll(
    tz: TradeZeroConnector,
    account: String,
    states: Map<String, TickerState>,
    openShorts: MutableMap<String, OpenShort>,
    journal: TradeJournal,
    risk: RiskManager,
): Int {
    var closed = 0
    for (ticker in openShorts.keys.toList()) {
        val open = openShorts[ticker] ?: continue
        val price = states[ticker]?.lastPrice?.takeIf { it > 0.0 } ?: open.entryPrice
        runCatching { tz.flatten(account, ticker) }
            .onSuccess { r ->
                if (r.accepted) {
                    val trade = journal.recordExit(ticker, price, "KILL")
                    val realized = trade?.realizedPnlUsd ?: realizedPnl(TradeSide.SHORT, open.shares, open.entryPrice, price)
                    risk.registerRealizedPnl(realized)
                    risk.closePosition(ticker)
                    risk.recordDayTrade()
                    openShorts.remove(ticker)
                    closed++
                }
            }.onFailure { log("flatten $ticker failed: ${it.message}") }
    }
    return closed
}

/** Warn if the broker's open shorts don't match what we track locally (phantom/stale positions). */
private suspend fun reconcile(
    tz: TradeZeroConnector,
    account: String,
    openShorts: Map<String, OpenShort>,
    notifier: Notifier,
) {
    val brokerShorts =
        tz
            .getPositions(account)
            .filter { (it["shares"]?.jsonPrimitive?.doubleOrNull ?: 0.0) < 0.0 }
            .mapNotNull { it["symbol"]?.jsonPrimitive?.content }
            .toSet()
    val local = openShorts.keys.toSet()
    val brokerOnly = brokerShorts - local
    val localOnly = local - brokerShorts
    if (brokerOnly.isNotEmpty() || localOnly.isNotEmpty()) {
        notifier.send("⚠️ position mismatch — broker-only=$brokerOnly local-only=$localOnly")
    }
}

/**
 * Detect likely LULD halts on tickers we hold: if a ticker stops updating for
 * [StrategyParams.haltSeconds], freeze (we can't fill during a halt anyway) and alert
 * once. Clears the flag when quotes resume.
 */
private suspend fun checkHalts(
    states: Map<String, TickerState>,
    openShorts: Map<String, OpenShort>,
    params: StrategyParams,
    notifier: Notifier,
    flagged: MutableSet<String>,
    nowMs: Long,
) {
    for (ticker in openShorts.keys) {
        val state = states[ticker] ?: continue
        val stale = isStale(state.lastUpdateMs, nowMs, params.haltSeconds)
        if (stale && flagged.add(ticker)) {
            notifier.send("⏸️ possible halt on $ticker (no quote ${params.haltSeconds}s) — holding, frozen")
        } else if (!stale) {
            flagged.remove(ticker)
        }
    }
}

private suspend fun resolveUniverse(scanner: Scanner): List<WatchlistItem> {
    val scanned =
        runCatching { scanner.scan() }
            .onFailure { log("scanner failed (${it.message}) — falling back to the watchlist file") }
            .getOrDefault(emptyList())
    if (scanned.isNotEmpty()) {
        log("scanner selected ${scanned.size} ticker(s)")
        return scanned
    }
    log("scanner returned no candidates — using the watchlist file")
    return loadWatchlistOrExample()
}

private fun loadWatchlistOrExample(): List<WatchlistItem> {
    val real = Path.of("data/watchlist.json")
    val example = Path.of("data/watchlist.example.json")
    return when {
        Files.exists(real) -> Watchlist.load(real)
        Files.exists(example) -> {
            log("WARNING: data/watchlist.json not found — using example watchlist.")
            Watchlist.load(example)
        }
        else -> {
            log("WARNING: no watchlist file and the scanner returned nothing — empty universe (idle).")
            emptyList()
        }
    }
}
