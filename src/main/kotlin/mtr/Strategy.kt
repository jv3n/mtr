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
    var wasAboveVwap: Boolean = false, // VWAP side on the PREVIOUS tick (for cross detection)
    var lastUpdateMs: Long = 0L, // wall-clock of the last quote (for halt detection)
)

/** Tunable strategy parameters. */
data class StrategyParams(
    val entryPctChange: Double = 0.5, // require a strong pump (+50% from open) before shorting
    val minRetraceFromHighPct: Double = 0.03, // price must be ≥3% off the day high (rolling over)
    val stopLossPct: Double = 0.05, // cover if the short loses 5%
    val takeProfitPct: Double = 0.10, // cover if the short gains 10%
    val maxHoldSeconds: Long = 1800, // time stop: cover after 30 min if no stop/TP hit
    val haltSeconds: Long = 60, // no quote for this long → the ticker is likely halted
    val perTradeUsd: Double = 2000.0, // target notional per short
)

enum class ExitReason { NONE, STOP_LOSS, TAKE_PROFIT, TIME }

/**
 * Entry evaluation (v1). Short a pump & dump on the momentum flip, requiring three
 * things together:
 *  1. **Pump**: up ≥ [StrategyParams.entryPctChange] from the open (only trade real spikes).
 *  2. **VWAP reject**: price crosses from above to below VWAP this tick (momentum flips down).
 *  3. **Rolling over**: price is ≥ [StrategyParams.minRetraceFromHighPct] off the day high
 *     (don't short into strength / at the highs).
 *
 * TODO: add volume-exhaustion confirmation once we track per-bar volume.
 */
fun evaluate(
    state: TickerState,
    params: StrategyParams = StrategyParams(),
): Signal {
    val pumped = state.pctChange >= params.entryPctChange
    val below = state.vwap > 0.0 && state.lastPrice < state.vwap
    val crossedDownThroughVwap = state.wasAboveVwap && below
    val rolledOver = state.dayHigh > 0.0 && state.lastPrice <= state.dayHigh * (1 - params.minRetraceFromHighPct)

    if (pumped && crossedDownThroughVwap && rolledOver) {
        val offHigh = if (state.dayHigh > 0.0) (state.dayHigh - state.lastPrice) / state.dayHigh else 0.0
        return Signal(
            state.ticker,
            SignalType.SHORT,
            confidence = 0.5,
            reason = "VWAP reject after +%.0f%%, %.0f%% off high".format(state.pctChange * 100, offHigh * 100),
        )
    }
    return Signal(state.ticker, SignalType.NONE, 0.0, "no signal")
}

/**
 * Exit decision for an OPEN short entered at [entryPrice], given the current price.
 * For a short, profit rises as price falls.
 */
fun shouldExit(
    entryPrice: Double,
    currentPrice: Double,
    heldSeconds: Long,
    params: StrategyParams = StrategyParams(),
): ExitReason {
    if (entryPrice <= 0.0) return ExitReason.NONE
    val gainPct = (entryPrice - currentPrice) / entryPrice // >0 = profit
    return when {
        gainPct >= params.takeProfitPct -> ExitReason.TAKE_PROFIT
        gainPct <= -params.stopLossPct -> ExitReason.STOP_LOSS
        heldSeconds >= params.maxHoldSeconds -> ExitReason.TIME
        else -> ExitReason.NONE
    }
}

/** A ticker that hasn't updated for [StrategyParams.haltSeconds] is likely in an LULD halt. */
fun isStale(
    lastUpdateMs: Long,
    nowMs: Long,
    haltSeconds: Long,
): Boolean = lastUpdateMs > 0L && (nowMs - lastUpdateMs) >= haltSeconds * 1000
