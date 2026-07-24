package mtr

import java.time.LocalTime
import kotlin.math.floor

/**
 * Dump-detection strategy per ticker + exit rules.
 *
 * Detects the reversal of a pump & dump at the open and manages the exit.
 * Produces SIGNALS and EXIT decisions; it does not place orders (main + risk do).
 *
 * Entry follows the GUS pattern (`docs/pattern/gus.md`): qualify on the overnight gap,
 * time the entry on the fade. Position management follows the Double Top lesson
 * (`docs/pattern/dt.md`, #16): breathing room on the stop, a trailing stop that only arms
 * after the trade has worked, no new entry late in the session, and reduced size under SSR.
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
    /** Previous session's close — the Rule 201 reference for arming SSR intraday. */
    val prevClose: Double = 0.0,
    /** SSR carried over from the previous session (see [MarketDataProvider.getInheritedSsr]). */
    val ssrInherited: Boolean = false,
    /** Latches once Rule 201 trips today: SSR holds for the rest of the session. */
    var ssrTrippedToday: Boolean = false,
    var pctChange: Double = 0.0, // fraction since the open (0.5 = +50%, NEGATIVE while fading)
    var wasAboveVwap: Boolean = false, // VWAP side on the PREVIOUS tick (for cross detection)
    var lastUpdateMs: Long = 0L, // wall-clock of the last quote (for halt detection)
) {
    /** Shorting is allowed but restricted to upticks — we treat it as a red flag, not a veto. */
    val ssrActive: Boolean get() = ssrInherited || ssrTrippedToday
}

/**
 * Reg SHO Rule 201: a short-sale restriction arms as soon as a stock trades 10 % or more
 * below the previous session's close, and stays on for the rest of that day and all of the
 * next one.
 *
 * Pure, and used twice: on today's session low against yesterday's close (intraday), and on
 * yesterday's low against the close before it (inherited).
 */
fun ssrTriggered(
    low: Double,
    referenceClose: Double,
): Boolean = referenceClose > 0.0 && low > 0.0 && low <= referenceClose * 0.90

/** Tunable strategy parameters. */
data class StrategyParams(
    /**
     * GUS #2 — qualify the OVERNIGHT GAP, not the move since the open (see [evaluate]).
     * Kept in step with [ScanCriteria.minGapPct], which selects the same universe.
     */
    val minGapPct: Double = 0.50,
    /** Price must be ≥3 % off the day high — i.e. actually rolling over, not still pushing. */
    val minRetraceFromHighPct: Double = 0.03,
    /**
     * Breathing room above the entry, from the worked TRIB case in `docs/pattern/dt.md`:
     * entry 3.14, stop 3.49 → 11 %. The point of the width is to absorb a minor poke through
     * the top instead of being shaken out by it; the old 5 % would have cut at 3.30, before
     * the trade had room to work at all.
     *
     * (`dt.md` also says "risk ~25 % of the position". That is the one figure with no worked
     * example behind it, in a transcript the document itself calls fragmentary, so it is read
     * as a tolerance after slippage rather than as the stop distance.)
     */
    val stopLossPct: Double = 0.11,
    /** Cover once the short is 10 % in profit — the lesson's usual target. */
    val takeProfitPct: Double = 0.10,
    /**
     * Trailing stop, armed only once the trade has proven itself: the lesson is explicit
     * about NOT tightening early, since a jumpy trail would undo the breathing room above.
     */
    val trailingArmPct: Double = 0.05,
    /** Once armed, cover as soon as this much of the best price is handed back. */
    val trailingGivebackPct: Double = 0.03,
    /** Time stop: cover after 30 min if neither stop nor target was hit. */
    val maxHoldSeconds: Long = 1800,
    /** No quote for this long → the ticker is likely in an LULD halt. */
    val haltSeconds: Long = 60,
    /**
     * Risk budget per trade. Size follows from this and [stopLossPct] rather than being a
     * fixed notional, so widening the stop does NOT quietly multiply the loss a single trade
     * can inflict on `RiskLimits.maxDailyLossUsd`.
     */
    val riskPerTradeUsd: Double = 100.0,
    /** Hard ceiling on a single line, whatever the risk maths work out to. */
    val maxNotionalUsd: Double = 2000.0,
    /**
     * Size multiplier for a name under SSR. Shorting is still allowed there — uptick-only —
     * but the restriction means our side has less ability to press, so it is a red flag that
     * costs size rather than a veto.
     */
    val ssrSizeMultiplier: Double = 0.5,
    /**
     * No NEW position after this time (New York). Late-session pushes tend not to come back
     * down. Deliberately distinct from `SESSION_END`, which is when everything is flattened:
     * stopping entries and closing the book are two different moments.
     */
    val entryCutoff: LocalTime = LocalTime.of(15, 0),
)

enum class ExitReason { NONE, STOP_LOSS, TAKE_PROFIT, TRAILING_STOP, TIME }

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
 * For a short, profit rises as price falls, so [lowestPrice] — the lowest print seen since
 * entry — is the best the trade has ever been, and the anchor for the trailing stop.
 *
 * Defaulting [lowestPrice] to [currentPrice] means "no trail history", which can never fire
 * the trailing branch.
 */
fun shouldExit(
    entryPrice: Double,
    currentPrice: Double,
    heldSeconds: Long,
    lowestPrice: Double = currentPrice,
    params: StrategyParams = StrategyParams(),
): ExitReason {
    if (entryPrice <= 0.0) return ExitReason.NONE
    val gainPct = (entryPrice - currentPrice) / entryPrice // >0 = profit
    val bestGainPct = if (lowestPrice > 0.0) (entryPrice - lowestPrice) / entryPrice else 0.0
    // Only once the trade has proven itself does giving profit back become a reason to leave.
    val trailArmed = bestGainPct >= params.trailingArmPct
    val givebackPct = if (lowestPrice > 0.0) (currentPrice - lowestPrice) / lowestPrice else 0.0
    return when {
        gainPct >= params.takeProfitPct -> ExitReason.TAKE_PROFIT
        gainPct <= -params.stopLossPct -> ExitReason.STOP_LOSS
        trailArmed && givebackPct >= params.trailingGivebackPct -> ExitReason.TRAILING_STOP
        heldSeconds >= params.maxHoldSeconds -> ExitReason.TIME
        else -> ExitReason.NONE
    }
}

/**
 * Shares to short, derived from the RISK budget: a stop at [StrategyParams.stopLossPct]
 * should cost about [StrategyParams.riskPerTradeUsd], never more than
 * [StrategyParams.maxNotionalUsd] of exposure. [ssrActive] halves it (red flag, not a veto).
 *
 * Sizing off risk rather than off a fixed notional is what keeps a wider stop from silently
 * eating the daily loss budget in fewer trades.
 */
fun shareCount(
    price: Double,
    ssrActive: Boolean = false,
    params: StrategyParams = StrategyParams(),
): Int {
    if (price <= 0.0 || params.stopLossPct <= 0.0) return 0
    val fromRisk = params.riskPerTradeUsd / params.stopLossPct
    val multiplier = if (ssrActive) params.ssrSizeMultiplier else 1.0
    val notional = minOf(fromRisk, params.maxNotionalUsd) * multiplier
    return floor(notional / price).toInt()
}

/**
 * Whether a NEW position may still be opened at [now] (New York time). Exits are never
 * gated by this — a position already on must always be able to close.
 */
fun entriesAllowedAt(
    now: LocalTime,
    params: StrategyParams = StrategyParams(),
): Boolean = now.isBefore(params.entryCutoff)

/** A ticker that hasn't updated for [StrategyParams.haltSeconds] is likely in an LULD halt. */
fun isStale(
    lastUpdateMs: Long,
    nowMs: Long,
    haltSeconds: Long,
): Boolean = lastUpdateMs > 0L && (nowMs - lastUpdateMs) >= haltSeconds * 1000
