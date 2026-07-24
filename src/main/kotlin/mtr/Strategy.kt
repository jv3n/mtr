package mtr

/**
 * Dump-detection strategy per ticker + exit rules.
 *
 * Detects the reversal of a pump & dump at the open and manages the exit.
 * Produces SIGNALS and EXIT decisions; it does not place orders (main + risk do).
 *
 * Entry follows the GUS pattern (`docs/pattern/gus.md`): qualify on the overnight gap,
 * time the entry on the fade. Exits are still the generic stop/TP/time set — aligning
 * them on the pattern (wider stop, trailing) is #16.
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
    /**
     * Overnight gap, set once by the scanner and constant for the session (the open does
     * not move). Null = unmeasured, which never qualifies for a short. See [evaluate].
     */
    val gapPct: Double? = null,
    var lastPrice: Double = 0.0,
    var vwap: Double = 0.0,
    var dayOpen: Double = 0.0,
    var dayHigh: Double = 0.0,
    var pctChange: Double = 0.0, // fraction since the open (0.5 = +50%, NEGATIVE while fading)
    var wasAboveVwap: Boolean = false, // VWAP side on the PREVIOUS tick (for cross detection)
    var lastUpdateMs: Long = 0L, // wall-clock of the last quote (for halt detection)
)

/** Tunable strategy parameters. */
data class StrategyParams(
    /**
     * GUS #2 — qualify the OVERNIGHT GAP, not the move since the open (see [evaluate]).
     * Kept in step with [ScanCriteria.minGapPct], which selects the same universe.
     */
    val minGapPct: Double = 0.50,
    val minRetraceFromHighPct: Double = 0.03, // price must be ≥3% off the day high (rolling over)
    val stopLossPct: Double = 0.05, // cover if the short loses 5%
    val takeProfitPct: Double = 0.10, // cover if the short gains 10%
    val maxHoldSeconds: Long = 1800, // time stop: cover after 30 min if no stop/TP hit
    val haltSeconds: Long = 60, // no quote for this long → the ticker is likely halted
    val perTradeUsd: Double = 2000.0, // target notional per short
)

enum class ExitReason { NONE, STOP_LOSS, TAKE_PROFIT, TIME }

/**
 * Entry evaluation (GUS, #15). Short a gap-and-fade, requiring three things together:
 *  1. **Gapped**: the OVERNIGHT gap is ≥ [StrategyParams.minGapPct] — the pattern's
 *     qualifier, and what leaves room below for the short to pay.
 *  2. **VWAP reject**: price crosses from above to below VWAP this tick (momentum flips down).
 *  3. **Rolling over**: price is ≥ [StrategyParams.minRetraceFromHighPct] off the day high
 *     (don't short into strength / at the highs).
 *
 * The qualifier used to be `pctChange >= entryPctChange`, i.e. +50 % **above the open**.
 * That is structurally the opposite of this setup: the GUS ticker gaps up and then fades,
 * so its price falls *below* the open, `pctChange` goes negative, and the condition could
 * essentially never fire. Only the qualifier moved — VWAP reject and roll-over are timing
 * triggers and stay as they are.
 *
 * An unmeasured gap (`state.gapPct == null`) never qualifies: refusing to trade on data we
 * do not have is the safe side, the same rule the borrow check follows.
 *
 * TODO: add volume-exhaustion confirmation once we track per-bar volume.
 */
fun evaluate(
    state: TickerState,
    params: StrategyParams = StrategyParams(),
): Signal {
    val gap = state.gapPct
    val gapped = gap != null && gap >= params.minGapPct
    val below = state.vwap > 0.0 && state.lastPrice < state.vwap
    val crossedDownThroughVwap = state.wasAboveVwap && below
    val rolledOver = state.dayHigh > 0.0 && state.lastPrice <= state.dayHigh * (1 - params.minRetraceFromHighPct)

    if (gapped && crossedDownThroughVwap && rolledOver) {
        val offHigh = if (state.dayHigh > 0.0) (state.dayHigh - state.lastPrice) / state.dayHigh else 0.0
        return Signal(
            state.ticker,
            SignalType.SHORT,
            confidence = 0.5,
            reason =
                "gap +%.0f%%, VWAP reject, %.0f%% off high (%+.0f%% from open)".format(
                    gap * 100,
                    offHigh * 100,
                    state.pctChange * 100,
                ),
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
