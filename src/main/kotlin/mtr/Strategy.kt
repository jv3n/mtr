package mtr

/**
 * Dump-detection strategy per ticker + exit rules.
 *
 * Detects the reversal of a pump & dump at the open and manages the exit.
 * Produces SIGNALS and EXIT decisions; it does not place orders (main + risk do).
 *
 * The detection logic here is a first, simple version — to be refined with real
 * criteria (volume exhaustion, VWAP reclaim/reject, etc.).
 */

enum class SignalType { NONE, SHORT }

data class Signal(
    val ticker: String,
    val type: SignalType,
    val confidence: Double, // 0..1
    val reason: String,
)

/** Current state of a ticker, updated by the real-time feed. */
data class TickerState(
    val ticker: String,
    var lastPrice: Double = 0.0,
    var vwap: Double = 0.0,
    var dayOpen: Double = 0.0,
    var dayHigh: Double = 0.0,
    var pctChange: Double = 0.0, // fraction since the open (0.5 = +50%)
)

/** Tunable strategy parameters. */
data class StrategyParams(
    val entryPctChange: Double = 0.5, // require a strong pump (+50% from open) before shorting
    val stopLossPct: Double = 0.05, // cover if the short loses 5%
    val takeProfitPct: Double = 0.10, // cover if the short gains 10%
    val perTradeUsd: Double = 2000.0, // target notional per short
)

enum class ExitReason { NONE, STOP_LOSS, TAKE_PROFIT }

/**
 * Entry evaluation: short when a strong pump breaks back below VWAP (reversal).
 * TODO: refine with volume/exhaustion signals.
 */
fun evaluate(
    state: TickerState,
    params: StrategyParams = StrategyParams(),
): Signal {
    if (state.pctChange >= params.entryPctChange && state.vwap > 0 && state.lastPrice < state.vwap) {
        return Signal(
            state.ticker,
            SignalType.SHORT,
            confidence = 0.5,
            reason = "break below VWAP after +%.0f%%".format(state.pctChange * 100),
        )
    }
    return Signal(state.ticker, SignalType.NONE, 0.0, "no signal")
}

/**
 * Exit decision for an OPEN short entered at [entryPrice], given the current price.
 * For a short, profit rises as price falls.
 */
fun shouldExitShort(
    entryPrice: Double,
    currentPrice: Double,
    params: StrategyParams = StrategyParams(),
): ExitReason {
    if (entryPrice <= 0.0) return ExitReason.NONE
    val gainPct = (entryPrice - currentPrice) / entryPrice // >0 = profit
    return when {
        gainPct >= params.takeProfitPct -> ExitReason.TAKE_PROFIT
        gainPct <= -params.stopLossPct -> ExitReason.STOP_LOSS
        else -> ExitReason.NONE
    }
}
