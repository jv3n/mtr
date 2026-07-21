package mtr

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class JournalTest {
    private fun trade(pnl: Double) =
        Trade(
            ticker = "T",
            side = TradeSide.SHORT,
            shares = 10,
            entryPrice = 10.0,
            exitPrice = 10.0,
            entryReason = "",
            exitReason = "",
            realizedPnlUsd = pnl,
            entryAt = Instant.EPOCH,
            exitAt = Instant.EPOCH,
        )

    @Test
    fun `empty summary is zeroed`() {
        val s = summarize(emptyList())
        assertEquals(0, s.trades)
        assertEquals(0.0, s.realizedPnlUsd)
        assertEquals(0.0, s.winRate)
    }

    @Test
    fun `counts wins losses and pnl`() {
        val s = summarize(listOf(trade(100.0), trade(-40.0), trade(60.0)))
        assertEquals(3, s.trades)
        assertEquals(2, s.wins)
        assertEquals(1, s.losses)
        assertEquals(120.0, s.realizedPnlUsd)
        assertEquals(2.0 / 3.0, s.winRate)
        assertEquals(80.0, s.avgWinUsd) // (100 + 60) / 2
        assertEquals(-40.0, s.avgLossUsd)
        assertEquals(100.0, s.bestUsd)
        assertEquals(-40.0, s.worstUsd)
    }

    @Test
    fun `max drawdown on the cumulative equity curve`() {
        // cumulative: +100 (peak 100) -> -50 (drawdown 150) -> +30 (still peak 100)
        val s = summarize(listOf(trade(100.0), trade(-150.0), trade(80.0)))
        assertEquals(150.0, s.maxDrawdownUsd)
        assertEquals(30.0, s.realizedPnlUsd)
    }

    @Test
    fun `realizedPnl for short and long`() {
        assertEquals(20.0, realizedPnl(TradeSide.SHORT, 10, 5.0, 3.0)) // (5 - 3) * 10
        assertEquals(20.0, realizedPnl(TradeSide.LONG, 10, 3.0, 5.0)) // (5 - 3) * 10
    }
}
