package mtr

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalsTest {
    @Test
    fun `scan digest lists each candidate with its note`() {
        val digest =
            formatScanDigest(
                listOf(
                    WatchlistItem("AAA", note = "gap +90%, +30% today, vol 1000000"),
                    WatchlistItem("BBB", note = null),
                ),
            )
        assertContains(digest, "2 candidat")
        assertContains(digest, "• AAA — gap +90%")
        assertContains(digest, "• BBB")
    }

    @Test
    fun `scan digest handles an empty universe`() {
        assertContains(formatScanDigest(emptyList()), "aucun candidat")
    }

    @Test
    fun `entry signal places the short stop above and the target below the entry`() {
        val state = TickerState(ticker = "TRIB", lastPrice = 3.14)
        val signal = Signal("TRIB", SignalType.SHORT, 0.5, "gap +90%, VWAP reject, 5% off high")
        val msg = formatEntrySignal(state, signal, shares = 300)

        assertContains(msg, "SIGNAL SHORT TRIB — GUS")
        assertContains(msg, "gap +90%, VWAP reject")
        // Default stop 11 % above 3.14 = 3.49, take-profit 10 % below = 2.83.
        assertContains(msg, "3.49")
        assertContains(msg, "2.83")
    }

    @Test
    fun `entry signal flags SSR only when active`() {
        val signal = Signal("X", SignalType.SHORT, 0.5, "reason")
        val plain = formatEntrySignal(TickerState("X", lastPrice = 5.0), signal, 100)
        assertFalse(plain.contains("SSR"))

        val ssr = formatEntrySignal(TickerState("X", lastPrice = 5.0, ssrInherited = true), signal, 100)
        assertTrue(ssr.contains("SSR"))
    }

    @Test
    fun `entry signal reports the suggested size`() {
        val msg = formatEntrySignal(TickerState("X", lastPrice = 2.0), Signal("X", SignalType.SHORT, 0.5, "r"), 500)
        assertEquals(true, msg.contains("500 sh"))
    }
}
