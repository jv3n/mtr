package mtr

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Position management aligned on the Double Top lesson (#16, `docs/pattern/dt.md`). */
class PositionManagementTest {
    // --- Stop width -------------------------------------------------------------------

    @Test
    fun `the stop gives the setup the room the TRIB case needs`() {
        // TRIB: short at 3.14, stop at 3.49 (10% above the 3.18 double top).
        val params = StrategyParams()
        assertEquals(ExitReason.NONE, shouldExit(3.14, 3.40, heldSeconds = 0, params = params))
        assertEquals(ExitReason.STOP_LOSS, shouldExit(3.14, 3.50, heldSeconds = 0, params = params))
        // The old 5% stop cut at 3.30 — before the trade had room to work at all.
        assertEquals(
            ExitReason.STOP_LOSS,
            shouldExit(3.14, 3.40, heldSeconds = 0, params = params.copy(stopLossPct = 0.05)),
        )
    }

    // --- Trailing stop ----------------------------------------------------------------

    @Test
    fun `the trail stays disarmed until the trade has proven itself`() {
        // Only 3% of gain reached (best 9.70 from 10.00), under the 5% arming threshold,
        // and price has already come back up. Tightening here is exactly what the lesson
        // warns against.
        assertEquals(ExitReason.NONE, shouldExit(10.0, 10.05, heldSeconds = 0, lowestPrice = 9.70))
    }

    @Test
    fun `once armed, the trail covers when the best price is handed back`() {
        // Best was 9.00 (10% gain, armed). 3% giveback from 9.00 is 9.27.
        assertEquals(ExitReason.NONE, shouldExit(10.0, 9.20, heldSeconds = 0, lowestPrice = 9.00))
        assertEquals(ExitReason.TRAILING_STOP, shouldExit(10.0, 9.28, heldSeconds = 0, lowestPrice = 9.00))
    }

    @Test
    fun `taking profit still wins over the trail`() {
        // At 9.00 the 10% target is reached; it should not be reported as a trail exit.
        assertEquals(ExitReason.TAKE_PROFIT, shouldExit(10.0, 9.0, heldSeconds = 0, lowestPrice = 9.0))
    }

    @Test
    fun `no trail without price history`() {
        // lowestPrice defaults to the current price → nothing given back, nothing to trail.
        assertEquals(ExitReason.NONE, shouldExit(10.0, 9.8, heldSeconds = 0))
    }

    // --- Sizing -----------------------------------------------------------------------

    @Test
    fun `size comes from the risk budget, not from a fixed notional`() {
        // 100$ of risk over an 11% stop → ~909$ of exposure → 90 shares at 10$.
        assertEquals(90, shareCount(10.0))
    }

    @Test
    fun `a wider stop buys fewer shares, keeping the loss per trade flat`() {
        val tight = StrategyParams(stopLossPct = 0.05)
        val wide = StrategyParams(stopLossPct = 0.20)
        val lossAtStop = { p: StrategyParams -> shareCount(10.0, params = p) * 10.0 * p.stopLossPct }
        // Both risk ~the same dollars despite a 4x difference in stop width.
        assertTrue(kotlin.math.abs(lossAtStop(tight) - lossAtStop(wide)) < 5.0)
        assertTrue(shareCount(10.0, params = wide) < shareCount(10.0, params = tight))
    }

    @Test
    fun `the notional ceiling still caps a very tight stop`() {
        // A 1% stop would ask for 10 000$ of exposure; the ceiling holds it at 2 000$.
        assertEquals(200, shareCount(10.0, params = StrategyParams(stopLossPct = 0.01)))
    }

    @Test
    fun `SSR halves the size instead of blocking the trade`() {
        assertEquals(45, shareCount(10.0, ssrActive = true))
        assertTrue(shareCount(10.0, ssrActive = true) > 0, "SSR is a red flag, not a veto")
    }

    @Test
    fun `no shares on a nonsense price`() {
        assertEquals(0, shareCount(0.0))
    }

    // --- Entry window -----------------------------------------------------------------

    @Test
    fun `entries stop at the cutoff, well before the flatten time`() {
        assertTrue(entriesAllowedAt(LocalTime.of(10, 0)))
        assertTrue(entriesAllowedAt(LocalTime.of(14, 59)))
        assertFalse(entriesAllowedAt(LocalTime.of(15, 0)))
        assertFalse(entriesAllowedAt(LocalTime.of(15, 45)))
    }

    @Test
    fun `the cutoff is configurable`() {
        val params = StrategyParams(entryCutoff = LocalTime.of(11, 30))
        assertTrue(entriesAllowedAt(LocalTime.of(11, 0), params))
        assertFalse(entriesAllowedAt(LocalTime.of(12, 0), params))
    }

    // --- SSR (Reg SHO Rule 201) -------------------------------------------------------

    @Test
    fun `SSR arms at ten percent below the reference close`() {
        assertFalse(ssrTriggered(low = 9.10, referenceClose = 10.0)) // -9%
        assertTrue(ssrTriggered(low = 9.00, referenceClose = 10.0)) // exactly -10%
        assertTrue(ssrTriggered(low = 8.00, referenceClose = 10.0))
    }

    @Test
    fun `SSR needs both numbers to mean anything`() {
        assertFalse(ssrTriggered(low = 5.0, referenceClose = 0.0))
        assertFalse(ssrTriggered(low = 0.0, referenceClose = 10.0))
    }

    @Test
    fun `an inherited SSR is active from the first tick`() {
        val state = TickerState("T", prevClose = 10.0, ssrInherited = true)
        assertTrue(state.ssrActive)
    }

    @Test
    fun `SSR latches for the session once it trips`() {
        val state = TickerState("T", prevClose = 10.0)
        assertFalse(state.ssrActive)
        state.ssrTrippedToday = ssrTriggered(8.5, state.prevClose)
        assertTrue(state.ssrActive)
        // Price recovers above the threshold — the restriction still stands for the day.
        assertTrue(state.ssrActive)
    }
}
