package mtr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private fun si(
        ticker: String,
        daysToCover: Double,
    ) = ShortInterest(ticker, 1_000_000, daysToCover, 200_000, "2026-07-15")

    @Test
    fun `keeps every candidate when no maxDaysToCover is set`() {
        val candidates = listOf(WatchlistItem("A"), WatchlistItem("B"))
        val result = applyShortInterest(candidates, mapOf("A" to si("A", 12.0)))
        assertEquals(listOf("A", "B"), result.map { it.ticker })
    }

    @Test
    fun `drops candidates above maxDaysToCover`() {
        val candidates = listOf(WatchlistItem("A"), WatchlistItem("B"), WatchlistItem("C"))
        val shortInterest =
            mapOf(
                "A" to si("A", 1.5), // below the cap → kept
                "B" to si("B", 8.0), // crowded short → dropped
                "C" to si("C", 3.0), // exactly at the cap → kept
            )
        val result = applyShortInterest(candidates, shortInterest, ScanCriteria(maxDaysToCover = 3.0))
        assertEquals(listOf("A", "C"), result.map { it.ticker })
    }

    @Test
    fun `keeps tickers with no FINRA coverage even when filtering`() {
        val candidates = listOf(WatchlistItem("A"), WatchlistItem("NOCOVER"))
        val result =
            applyShortInterest(
                candidates,
                mapOf("A" to si("A", 1.0)),
                ScanCriteria(maxDaysToCover = 3.0),
            )
        assertEquals(listOf("A", "NOCOVER"), result.map { it.ticker })
        assertTrue(result.last().note!!.contains("DTC n/a"))
    }

    @Test
    fun `annotates the note with days to cover and settlement date`() {
        val result =
            applyShortInterest(
                listOf(WatchlistItem("A", note = "gainer +40%")),
                mapOf("A" to si("A", 2.5)),
            )
        val note = result.single().note!!
        assertTrue(note.startsWith("gainer +40%"), "keeps the original note: $note")
        assertTrue(note.contains("DTC 2.5"), "carries days-to-cover: $note")
        assertTrue(note.contains("2026-07-15"), "carries the settlement date: $note")
    }
}
