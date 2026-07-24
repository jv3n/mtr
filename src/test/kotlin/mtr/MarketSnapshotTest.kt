package mtr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The gap is the GUS qualifier (#15) — the one number the whole entry rests on. */
class MarketSnapshotTest {
    @Test
    fun `gap is measured from the open against the previous close`() {
        // Closed at 1.00, opened at 1.80, now fading at 1.20.
        val s = MarketSnapshot("A", price = 1.20, dayChangePct = 0.20, volume = 1_000_000, dayOpen = 1.80, prevClose = 1.00)
        assertEquals(0.80, s.gapPct, 1e-9)
        assertFalse(s.gapIsProvisional)
    }

    @Test
    fun `gap ignores the fade, unlike the move since the open`() {
        // Price is now BELOW the open: the move since the open is negative while the gap
        // that qualifies the setup is untouched. This divergence is the whole point of #15.
        val s = MarketSnapshot("A", price = 1.50, dayChangePct = 0.50, volume = 1_000_000, dayOpen = 1.80, prevClose = 1.00)
        assertEquals(0.80, s.gapPct, 1e-9)
        assertTrue(s.price < s.dayOpen, "the ticker is fading")
    }

    @Test
    fun `falls back to a provisional pre-market gap before the open`() {
        // 7 a.m. scan: no official open yet (day.o = 0), so the last pre-market print stands in.
        val s = MarketSnapshot("A", price = 1.60, dayChangePct = 0.60, volume = 50_000, dayOpen = 0.0, prevClose = 1.00)
        assertEquals(0.60, s.gapPct, 1e-9)
        assertTrue(s.gapIsProvisional)
    }

    @Test
    fun `gap is zero without a previous close`() {
        val s = MarketSnapshot("A", price = 5.0, dayChangePct = 0.0, volume = 0, dayOpen = 4.0, prevClose = 0.0)
        assertEquals(0.0, s.gapPct, 1e-9)
        assertFalse(s.gapIsProvisional)
    }
}
