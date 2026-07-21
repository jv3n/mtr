package mtr

import kotlin.test.Test
import kotlin.test.assertEquals

class ScannerTest {
    private fun snap(
        ticker: String,
        price: Double,
        chg: Double,
        vol: Long,
    ) = MarketSnapshot(ticker, price, chg, vol)

    @Test
    fun `filters by price, gain and volume, ranked by gain desc`() {
        val snaps =
            listOf(
                snap("A", 3.0, 0.60, 1_000_000), // ok, +60%
                snap("B", 5.0, 0.30, 800_000), // ok, +30%
                snap("C", 0.50, 0.90, 5_000_000), // price too low
                snap("D", 25.0, 0.80, 5_000_000), // price too high
                snap("E", 4.0, 0.10, 5_000_000), // gain too low
                snap("F", 4.0, 0.40, 100_000), // volume too low
            )
        val result = filterCandidates(snaps)
        assertEquals(listOf("A", "B"), result.map { it.ticker })
    }

    @Test
    fun `caps to maxResults keeping the biggest gainers`() {
        val snaps = (1..15).map { snap("T$it", 5.0, 0.20 + it * 0.01, 1_000_000) }
        val result = filterCandidates(snaps, ScanCriteria(maxResults = 3))
        assertEquals(listOf("T15", "T14", "T13"), result.map { it.ticker })
    }
}
