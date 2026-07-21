package mtr

import kotlin.test.Test
import kotlin.test.assertEquals

class StrategyTest {
    // A state that satisfies all three entry conditions (pump + VWAP reject + rolled over).
    private fun shortReadyState() =
        TickerState(
            ticker = "T",
            lastPrice = 1.60,
            vwap = 1.70,
            dayOpen = 1.00,
            dayHigh = 2.00,
            pctChange = 0.60, // +60% from open
            wasAboveVwap = true, // was above VWAP last tick → crosses down now
        )

    @Test
    fun `short on pump plus vwap reject plus rollover`() {
        assertEquals(SignalType.SHORT, evaluate(shortReadyState()).type)
    }

    @Test
    fun `no signal without a real pump`() {
        val s = shortReadyState().copy(pctChange = 0.10) // only +10%
        assertEquals(SignalType.NONE, evaluate(s).type)
    }

    @Test
    fun `no signal without a fresh vwap cross`() {
        val s = shortReadyState().copy(wasAboveVwap = false) // already below, no fresh reject
        assertEquals(SignalType.NONE, evaluate(s).type)
    }

    @Test
    fun `no signal when still at the highs`() {
        // Below VWAP and pumped, but only 0.5% off the high → not rolling over yet.
        val s = shortReadyState().copy(lastPrice = 1.99, vwap = 2.00)
        assertEquals(SignalType.NONE, evaluate(s).type)
    }

    @Test
    fun `exit short on take profit and stop loss`() {
        // entry 10.0, take-profit 10% → price 9.0; stop-loss 5% → price 10.5
        assertEquals(ExitReason.TAKE_PROFIT, shouldExitShort(10.0, 9.0))
        assertEquals(ExitReason.STOP_LOSS, shouldExitShort(10.0, 10.5))
        assertEquals(ExitReason.NONE, shouldExitShort(10.0, 9.8))
    }
}
