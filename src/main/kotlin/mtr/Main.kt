package mtr

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.floor

/**
 * Orchestration / autonomous paper-trading loop.
 *
 * Flow: load watchlist → subscribe to Massive quotes → on each quote, either
 * manage an open short (stop-loss / take-profit) or evaluate a new short entry
 * (ETB check + guardrails). Every trade and a periodic account P&L snapshot go
 * through the TradeJournal (roadmap #1) so we can watch the return.
 *
 * ⚠️ On the free Massive tier (15-min delayed) live signals will be sparse/unrealistic;
 * a paid real-time plan is required for a meaningful return.
 */

private data class OpenShort(
    val entryPrice: Double,
    val shares: Int,
)

private fun log(msg: String) = println("${Instant.now()} $msg")

fun main() =
    kotlinx.coroutines.runBlocking {
        val creds = TradeZeroCredentials.fromEnv()
        if (creds.environment != "paper") log("WARNING: LIVE ENVIRONMENT — real money.")

        val params = StrategyParams()
        val risk = RiskManager(RiskLimits())
        val journal = TradeJournal()
        val watchlist = loadWatchlistOrExample()
        val tickers = watchlist.map { it.ticker }
        log("Watchlist: $tickers")

        val tz = TradeZeroConnector(creds)
        val market = MassiveProvider.fromEnv()
        val account = tz.accountId()
        log("Account: $account (${creds.environment})")

        val states = tickers.associateWith { TickerState(it) }
        val openShorts = mutableMapOf<String, OpenShort>()

        try {
            coroutineScope {
                // Background: periodic P&L snapshot + running performance summary.
                launch {
                    while (isActive) {
                        runCatching {
                            val pnl = tz.getPnl(account)
                            val accountValue = pnl["accountValue"]?.jsonPrimitive?.doubleOrNull
                            val dayPnl = pnl["dayPnl"]?.jsonPrimitive?.doubleOrNull
                            val exposure = pnl["exposure"]?.jsonPrimitive?.doubleOrNull
                            journal.recordAccountPnl(accountValue, dayPnl, exposure)
                            log("PNL accountValue=$accountValue dayPnl=$dayPnl exposure=$exposure | ${journal.summary().format()}")
                        }.onFailure { log("pnl fetch failed: ${it.message}") }
                        delay(60_000)
                    }
                }

                // Resilient market-data loop (auto-reconnect for 24/7).
                while (isActive) {
                    runCatching {
                        market.streamQuotes(tickers).collect { q ->
                            val state = states[q.ticker] ?: return@collect
                            updateState(state, q)
                            onQuote(state, tz, risk, account, params, openShorts, journal)
                        }
                    }.onFailure { log("stream error: ${it.message} — reconnecting in 5s") }
                    delay(5_000)
                }
            }
        } finally {
            tz.close()
            market.close()
            log("FINAL ${journal.summary().format()}")
        }
    }

private fun updateState(
    s: TickerState,
    q: Quote,
) {
    // Record the VWAP side from the PREVIOUS tick before overwriting (for cross detection).
    s.wasAboveVwap = s.vwap > 0.0 && s.lastPrice >= s.vwap
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

    // 1. Manage an existing short: stop-loss / take-profit.
    if (open != null) {
        val exit = shouldExitShort(open.entryPrice, state.lastPrice, params)
        if (exit != ExitReason.NONE) {
            val result = tz.flatten(account, ticker)
            if (result.accepted) {
                val trade = journal.recordExit(ticker, state.lastPrice, exit.name)
                val realized = trade?.realizedPnlUsd ?: realizedPnl(TradeSide.SHORT, open.shares, open.entryPrice, state.lastPrice)
                risk.registerRealizedPnl(realized)
                risk.closePosition(ticker)
                openShorts.remove(ticker)
                log("EXIT $ticker ($exit) @ ${state.lastPrice} entry=${open.entryPrice} pnl=%.2f".format(realized))
            }
        }
        return
    }

    // 2. No position: look for a new short entry.
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

    // Only short easy-to-borrow names for now (locate WS not implemented yet).
    if (!tz.isEasyToBorrow(account, ticker)) {
        log("Short skipped for $ticker: hard-to-borrow (locate stream TODO)")
        return
    }

    val result = tz.submitShort(account, ticker, shares)
    if (result.accepted) {
        openShorts[ticker] = OpenShort(entryPrice = state.lastPrice, shares = shares)
        risk.registerFill(ticker, notional)
        journal.recordEntry(ticker, TradeSide.SHORT, shares, state.lastPrice, signal.reason)
        log("ENTER SHORT $ticker x$shares @ ${state.lastPrice} (${signal.reason})")
    } else {
        log("Order not accepted for $ticker: ${result.reason}")
    }
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
        else -> error("No watchlist found (data/watchlist.json).")
    }
}
