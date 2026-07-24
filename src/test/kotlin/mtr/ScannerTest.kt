package mtr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerTest {
    /** Defaults to a comfortably qualifying gap so each test can vary one thing at a time. */
    private fun snap(
        ticker: String,
        price: Double,
        chg: Double,
        vol: Long,
        gap: Double = 0.80,
        prevClose: Double = 1.0,
    ) = MarketSnapshot(
        ticker = ticker,
        price = price,
        dayChangePct = chg,
        volume = vol,
        dayOpen = prevClose * (1 + gap),
        prevClose = prevClose,
    )

    @Test
    fun `filters by price, gain, volume and gap`() {
        val snaps =
            listOf(
                snap("A", 3.0, 0.60, 1_000_000, gap = 0.90), // ok
                snap("B", 5.0, 0.30, 800_000, gap = 0.60), // ok
                snap("C", 0.50, 0.90, 5_000_000), // price too low
                snap("D", 25.0, 0.80, 5_000_000), // price too high
                snap("E", 4.0, 0.10, 5_000_000), // gap already given back
                snap("F", 4.0, 0.40, 100_000), // volume too low
                snap("G", 4.0, 0.40, 5_000_000, gap = 0.20), // gap too small
            )
        assertEquals(listOf("A", "B"), filterCandidates(snaps).map { it.ticker })
    }

    @Test
    fun `ranks by gap, not by the current change`() {
        val snaps =
            listOf(
                snap("SMALLGAP", 4.0, 0.95, 1_000_000, gap = 0.55), // biggest mover right now
                snap("BIGGAP", 4.0, 0.25, 1_000_000, gap = 1.50), // biggest overnight gap
            )
        assertEquals(listOf("BIGGAP", "SMALLGAP"), filterCandidates(snaps).map { it.ticker })
    }

    @Test
    fun `carries the gap into the item and its note`() {
        val item = filterCandidates(listOf(snap("A", 3.0, 0.60, 1_000_000, gap = 0.80))).single()
        assertEquals(0.80, item.gapPct!!, 1e-9)
        assertTrue(item.note!!.contains("gap +80%"), "note carries the gap: ${item.note}")
    }

    @Test
    fun `caps to maxResults keeping the biggest gaps`() {
        val snaps = (1..15).map { snap("T$it", 5.0, 0.30, 1_000_000, gap = 0.50 + it * 0.01) }
        val result = filterCandidates(snaps, ScanCriteria(maxResults = 3))
        assertEquals(listOf("T15", "T14", "T13"), result.map { it.ticker })
    }

    private fun ref(
        ticker: String,
        shares: Long,
    ) = TickerReference(ticker, sharesOutstanding = shares)

    @Test
    fun `drops candidates outside the float band`() {
        val candidates = listOf(WatchlistItem("TIGHT"), WatchlistItem("OK"), WatchlistItem("HUGE"))
        val refs =
            mapOf(
                "TIGHT" to ref("TIGHT", 1_000_000), // under 3M → too volatile for the pattern
                "OK" to ref("OK", 20_000_000),
                "HUGE" to ref("HUGE", 500_000_000), // far past the widened ceiling
            )
        assertEquals(listOf("OK"), applyFloatProxy(candidates, refs).map { it.ticker })
    }

    @Test
    fun `keeps a name whose outstanding exceeds the pattern's 50M float ceiling`() {
        // Outstanding is only an upper bound on float: 60M outstanding can still float under
        // 50M, so enforcing the pattern's ceiling on THIS quantity would drop it wrongly.
        val result = applyFloatProxy(listOf(WatchlistItem("A")), mapOf("A" to ref("A", 60_000_000)))
        assertEquals(listOf("A"), result.map { it.ticker })
    }

    @Test
    fun `keeps candidates whose float is unknown, and says so`() {
        val result = applyFloatProxy(listOf(WatchlistItem("A", note = "gap +80%")), emptyMap())
        assertEquals(listOf("A"), result.map { it.ticker })
        assertTrue(result.single().note!!.contains("float n/a"))
    }

    @Test
    fun `annotates the note with the float proxy`() {
        val result =
            applyFloatProxy(
                listOf(WatchlistItem("A", note = "gap +80%")),
                mapOf("A" to ref("A", 12_000_000)),
            )
        val note = result.single().note!!
        assertTrue(note.startsWith("gap +80%"), "keeps the original note: $note")
        assertTrue(note.contains("float ~12,000,000"), "carries the float proxy: $note")
    }

    @Test
    fun `drops candidates that ran a recent reverse split`() {
        val candidates = listOf(WatchlistItem("A"), WatchlistItem("RS"), WatchlistItem("B"))
        val result = applyReverseSplitFilter(candidates, setOf("RS"))
        assertEquals(listOf("A", "B"), result.map { it.ticker })
    }

    @Test
    fun `keeps every candidate when no reverse split is reported`() {
        val candidates = listOf(WatchlistItem("A"), WatchlistItem("B"))
        assertEquals(listOf("A", "B"), applyReverseSplitFilter(candidates, emptySet()).map { it.ticker })
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
                listOf(WatchlistItem("A", note = "gap +80%")),
                mapOf("A" to si("A", 2.5)),
            )
        val note = result.single().note!!
        assertTrue(note.startsWith("gap +80%"), "keeps the original note: $note")
        assertTrue(note.contains("DTC 2.5"), "carries days-to-cover: $note")
        assertTrue(note.contains("2026-07-15"), "carries the settlement date: $note")
    }
}
