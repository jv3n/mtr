package mtr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingEnvTest {
    @Test
    fun `recognises the live spellings`() {
        listOf("live", "LIVE", " Live ", "prod", "production", "real").forEach {
            assertEquals(TradingEnv.LIVE, TradingEnv.parse(it), "should be LIVE: '$it'")
        }
    }

    @Test
    fun `anything unknown falls back to paper, never to live`() {
        // A typo must never be what puts real money at risk.
        listOf(null, "", "  ", "paper", "PAPER", "liv", "lives", "prd", "no", "1", "true").forEach {
            assertEquals(TradingEnv.PAPER, TradingEnv.parse(it), "should be PAPER: '$it'")
        }
    }

    @Test
    fun `routes differ by environment`() {
        assertEquals("PAPER", TradingEnv.PAPER.defaultRoute)
        assertEquals("SMART", TradingEnv.LIVE.defaultRoute)
    }

    @Test
    fun `locates are only required on live`() {
        // TradeZero locates are live-only; paper shorts go through without one.
        assertFalse(TradingEnv.PAPER.requiresLocateForHardToBorrow)
        assertTrue(TradingEnv.LIVE.requiresLocateForHardToBorrow)
    }

    @Test
    fun `credentials default to paper`() {
        assertEquals(TradingEnv.PAPER, TradeZeroCredentials("k", "s").environment)
    }

    @Test
    fun `renders lowercase so log lines stay unchanged`() {
        assertEquals("paper", TradingEnv.PAPER.toString())
        assertEquals("live", TradingEnv.LIVE.toString())
    }
}
