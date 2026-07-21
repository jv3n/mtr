package mtr

/**
 * Autonomous universe scanner (roadmap #5).
 *
 * Picks the day's tradeable small-cap pump candidates from Massive's top gainers,
 * so the bot chooses its own tickers — no manual watchlist.
 *
 * `filterCandidates()` is PURE (no I/O) → unit-testable.
 */
data class ScanCriteria(
    val minPrice: Double = 1.0,
    val maxPrice: Double = 10.0,
    val minGainPct: Double = 0.20, // at least +20% on the day
    val minVolume: Long = 500_000, // enough liquidity to trade/borrow
    val maxResults: Int = 10,
)

/** Filter + rank gainer snapshots into the day's universe. Pure. */
fun filterCandidates(
    snapshots: List<MarketSnapshot>,
    criteria: ScanCriteria = ScanCriteria(),
): List<WatchlistItem> =
    snapshots
        .filter { it.price in criteria.minPrice..criteria.maxPrice }
        .filter { it.dayChangePct >= criteria.minGainPct }
        .filter { it.volume >= criteria.minVolume }
        .sortedByDescending { it.dayChangePct }
        .take(criteria.maxResults)
        .map {
            WatchlistItem(
                ticker = it.ticker,
                note = "gainer +%.0f%%, vol %d".format(it.dayChangePct * 100, it.volume),
            )
        }

class Scanner(
    private val provider: MarketDataProvider,
    private val criteria: ScanCriteria = ScanCriteria(),
) {
    /** Fetch top gainers and reduce them to the day's universe. */
    suspend fun scan(): List<WatchlistItem> = filterCandidates(provider.getGainers(), criteria)
}
