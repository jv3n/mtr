package mtr

import kotlin.test.Test
import kotlin.test.assertEquals

class StrategyTest {
    /**
     * A real GUS setup: gapped +80 % overnight, and now FADING — the price sits below the
     * open, so `pctChange` is negative. All three entry conditions hold.
     */
    private fun gapAndFadeState() =
        TickerState(
            ticker = "T",
            gapPct = 0.80, // opened 1.80 after a 1.00 close
            lastPrice = 1.60,
            vwap = 1.70,
            dayOpen = 1.80,
            dayHigh = 2.00,
            pctChange = -0.11, // 11% BELOW the open: it is fading, which is the point
            wasAboveVwap = true, // was above VWAP last tick → crosses down now
        )

    @Test
    fun `shorts a gap-and-fade even though the price is below the open`() {
        // The regression this issue is about: the old qualifier asked for +50% ABOVE the
        // open, which a fading gapper can never satisfy, so this setup could not fire.
        assertEquals(SignalType.SHORT, evaluate(gapAndFadeState()).type)
    }

    @Test
    fun `no signal when the gap is too small`() {
        val s = gapAndFadeState().copy(gapPct = 0.20) // +20% overnight: not a GUS setup
        assertEquals(SignalType.NONE, evaluate(s).type)
    }

    @Test
    fun `no signal when the gap is unknown`() {
        // Universe from a hand-written watchlist with no gap_pct: refuse rather than guess.
        val s = gapAndFadeState().copy(gapPct = null)
        assertEquals(SignalType.NONE, evaluate(s).type)
    }

    @Test
    fun `no signal without a fresh vwap cross`() {
        val s = gapAndFadeState().copy(wasAboveVwap = false) // already below, no fresh reject
        assertEquals(SignalType.NONE, evaluate(s).type)
    }

    @Test
    fun `no signal when still at the highs`() {
        // Below VWAP and gapped, but only 0.5% off the high → not rolling over yet.
        val s = gapAndFadeState().copy(lastPrice = 1.99, vwap = 2.00)
        assertEquals(SignalType.NONE, evaluate(s).type)
    }

    @Test
    fun `the reason carries the gap that qualified the trade`() {
        val reason = evaluate(gapAndFadeState()).reason
        assertEquals(true, reason.contains("gap +80%"), "reason states the gap: $reason")
    }

    @Test
    fun `exit short on take profit, stop loss and time`() {
        // entry 10.0, take-profit 10% → price 9.0; stop-loss 11% → price 11.10
        assertEquals(ExitReason.TAKE_PROFIT, shouldExit(10.0, 9.0, heldSeconds = 0))
        assertEquals(ExitReason.STOP_LOSS, shouldExit(10.0, 11.20, heldSeconds = 0))
        assertEquals(ExitReason.NONE, shouldExit(10.0, 9.8, heldSeconds = 0))
        // a 5% adverse move no longer stops the trade out — that is the point of #16
        assertEquals(ExitReason.NONE, shouldExit(10.0, 10.5, heldSeconds = 0))
        // held past maxHoldSeconds (default 1800) with no stop/TP → time stop
        assertEquals(ExitReason.TIME, shouldExit(10.0, 9.8, heldSeconds = 2000))
    }

    @Test
    fun `isStale flags a ticker that stopped updating`() {
        val now = 1_000_000L
        assertEquals(true, isStale(lastUpdateMs = now - 61_000, nowMs = now, haltSeconds = 60))
        assertEquals(false, isStale(lastUpdateMs = now - 10_000, nowMs = now, haltSeconds = 60))
        assertEquals(false, isStale(lastUpdateMs = 0L, nowMs = now, haltSeconds = 60)) // no data yet
    }
}
