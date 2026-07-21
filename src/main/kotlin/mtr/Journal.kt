package mtr

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Observability: structured trade journal + performance reporting (roadmap #1).
 *
 * - Records every entry/exit as a structured line in logs/trades.jsonl (auditable).
 * - Records periodic account P&L snapshots in logs/pnl.jsonl.
 * - Computes a readable performance summary (realized P&L, win rate, drawdown).
 *
 * `summarize()` is a PURE function over completed trades → unit-testable without I/O.
 */

enum class TradeSide { SHORT, LONG }

data class Trade(
    val ticker: String,
    val side: TradeSide,
    val shares: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val entryReason: String,
    val exitReason: String,
    val realizedPnlUsd: Double,
    val entryAt: Instant,
    val exitAt: Instant,
)

data class PerformanceSummary(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double, // 0..1
    val realizedPnlUsd: Double,
    val avgWinUsd: Double,
    val avgLossUsd: Double,
    val maxDrawdownUsd: Double, // largest peak-to-trough on the cumulative realized-P&L curve
    val bestUsd: Double,
    val worstUsd: Double,
)

/** Realized P&L for a closed position. For a short, profit rises as price falls. */
fun realizedPnl(
    side: TradeSide,
    shares: Int,
    entryPrice: Double,
    exitPrice: Double,
): Double =
    when (side) {
        TradeSide.SHORT -> (entryPrice - exitPrice) * shares
        TradeSide.LONG -> (exitPrice - entryPrice) * shares
    }

/** Pure aggregation over completed trades — no time, no I/O. */
fun summarize(trades: List<Trade>): PerformanceSummary {
    val n = trades.size
    val wins = trades.filter { it.realizedPnlUsd > 0.0 }
    val losses = trades.filter { it.realizedPnlUsd < 0.0 }

    var cumulative = 0.0
    var peak = 0.0
    var maxDrawdown = 0.0
    for (t in trades) {
        cumulative += t.realizedPnlUsd
        peak = maxOf(peak, cumulative)
        maxDrawdown = maxOf(maxDrawdown, peak - cumulative)
    }

    return PerformanceSummary(
        trades = n,
        wins = wins.size,
        losses = losses.size,
        winRate = if (n > 0) wins.size.toDouble() / n else 0.0,
        realizedPnlUsd = trades.sumOf { it.realizedPnlUsd },
        avgWinUsd = if (wins.isNotEmpty()) wins.sumOf { it.realizedPnlUsd } / wins.size else 0.0,
        avgLossUsd = if (losses.isNotEmpty()) losses.sumOf { it.realizedPnlUsd } / losses.size else 0.0,
        maxDrawdownUsd = maxDrawdown,
        bestUsd = trades.maxOfOrNull { it.realizedPnlUsd } ?: 0.0,
        worstUsd = trades.minOfOrNull { it.realizedPnlUsd } ?: 0.0,
    )
}

fun PerformanceSummary.format(): String =
    buildString {
        append("PERF  trades=%d  win=%.0f%% (%d/%d)  ".format(trades, winRate * 100, wins, losses))
        append("realized=%.2f  avgWin=%.2f  avgLoss=%.2f  ".format(realizedPnlUsd, avgWinUsd, avgLossUsd))
        append("maxDD=%.2f  best=%.2f  worst=%.2f".format(maxDrawdownUsd, bestUsd, worstUsd))
    }

class TradeJournal(
    private val tradesFile: Path = Path.of("logs/trades.jsonl"),
    private val pnlFile: Path = Path.of("logs/pnl.jsonl"),
    private val now: () -> Instant = Instant::now,
) {
    private data class OpenEntry(
        val side: TradeSide,
        val shares: Int,
        val entryPrice: Double,
        val reason: String,
        val at: Instant,
    )

    private val open = mutableMapOf<String, OpenEntry>()
    private val completed = mutableListOf<Trade>()

    val trades: List<Trade> get() = completed.toList()

    fun recordEntry(
        ticker: String,
        side: TradeSide,
        shares: Int,
        price: Double,
        reason: String,
    ) {
        val at = now()
        open[ticker] = OpenEntry(side, shares, price, reason, at)
        append(
            tradesFile,
            buildJsonObject {
                put("event", "entry")
                put("at", at.toString())
                put("ticker", ticker)
                put("side", side.name)
                put("shares", shares)
                put("price", price)
                put("reason", reason)
            }.toString(),
        )
    }

    /**
     * Close the open position for [ticker]. Returns the completed Trade, or null if
     * there was no matching open entry.
     */
    fun recordExit(
        ticker: String,
        exitPrice: Double,
        reason: String,
    ): Trade? {
        val e = open.remove(ticker) ?: return null
        val at = now()
        val pnl = realizedPnl(e.side, e.shares, e.entryPrice, exitPrice)
        val trade =
            Trade(
                ticker = ticker,
                side = e.side,
                shares = e.shares,
                entryPrice = e.entryPrice,
                exitPrice = exitPrice,
                entryReason = e.reason,
                exitReason = reason,
                realizedPnlUsd = pnl,
                entryAt = e.at,
                exitAt = at,
            )
        completed += trade
        append(
            tradesFile,
            buildJsonObject {
                put("event", "exit")
                put("at", at.toString())
                put("ticker", ticker)
                put("side", e.side.name)
                put("shares", e.shares)
                put("entryPrice", e.entryPrice)
                put("exitPrice", exitPrice)
                put("reason", reason)
                put("realizedPnlUsd", pnl)
            }.toString(),
        )
        return trade
    }

    fun recordAccountPnl(
        accountValue: Double?,
        dayPnl: Double?,
        exposure: Double?,
    ) {
        append(
            pnlFile,
            buildJsonObject {
                put("at", now().toString())
                put("accountValue", accountValue ?: 0.0)
                put("dayPnl", dayPnl ?: 0.0)
                put("exposure", exposure ?: 0.0)
            }.toString(),
        )
    }

    fun summary(): PerformanceSummary = summarize(completed)

    private fun append(
        file: Path,
        line: String,
    ) {
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            file,
            line + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
