package mtr

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskManagerTest {
    @Test
    fun `allows a short within limits`() {
        assertTrue(RiskManager(RiskLimits()).canOpenShort("AAA", 1000.0).first)
    }

    @Test
    fun `rejects a position that is too large`() {
        val r = RiskManager(RiskLimits(maxPositionUsd = 500.0))
        assertFalse(r.canOpenShort("AAA", 1000.0).first)
    }

    @Test
    fun `rejects beyond gross exposure`() {
        val r = RiskManager(RiskLimits(maxGrossExposureUsd = 1500.0, maxPositionUsd = 2000.0))
        r.registerFill("AAA", 1000.0)
        assertFalse(r.canOpenShort("BBB", 1000.0).first) // 1000 + 1000 > 1500
    }

    @Test
    fun `rejects beyond max positions`() {
        val r = RiskManager(RiskLimits(maxPositions = 1, maxGrossExposureUsd = 1e9, maxPositionUsd = 1e9))
        r.registerFill("AAA", 100.0)
        assertFalse(r.canOpenShort("BBB", 100.0).first)
    }

    @Test
    fun `daily loss trips the kill switch and blocks new shorts`() {
        val r = RiskManager(RiskLimits(maxDailyLossUsd = 500.0))
        r.registerRealizedPnl(-600.0)
        assertTrue(r.halted)
        assertFalse(r.canOpenShort("AAA", 100.0).first)
    }

    @Test
    fun `manual kill blocks new shorts`() {
        val r = RiskManager(RiskLimits())
        r.kill()
        assertTrue(r.halted)
        assertFalse(r.canOpenShort("AAA", 100.0).first)
    }
}
