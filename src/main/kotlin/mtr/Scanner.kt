package mtr

import java.time.Instant

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
    /**
     * Squeeze guard: drop candidates whose days-to-cover exceeds this.
     *
     * Null (default) = report only, never filter. Short interest is still fetched and
     * attached to each candidate's note, so the numbers can be observed on real sessions
     * before a threshold is committed to. Set it once the data justifies a value.
     */
    val maxDaysToCover: Double? = null,
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

/**
 * Squeeze guard. Annotates each candidate with its short interest and drops those above
 * `criteria.maxDaysToCover`. Pure (no I/O) → unit-testable.
 *
 * Tickers with no FINRA coverage are KEPT and marked `DTC n/a`: coverage gaps are routine
 * (recent listings, some OTC names) and dropping them would silently shrink the universe
 * without saying so. The guard filters on data it has, never on data it lacks.
 */
fun applyShortInterest(
    candidates: List<WatchlistItem>,
    shortInterest: Map<String, ShortInterest>,
    criteria: ScanCriteria = ScanCriteria(),
): List<WatchlistItem> =
    candidates.mapNotNull { item ->
        val si =
            shortInterest[item.ticker]
                ?: return@mapNotNull item.copy(note = "${item.note.orEmpty()} · DTC n/a".trim())
        if (criteria.maxDaysToCover != null && si.daysToCover > criteria.maxDaysToCover) {
            return@mapNotNull null
        }
        item.copy(
            note =
                "%s · DTC %.1f · SI %,d (%s)"
                    .format(item.note.orEmpty(), si.daysToCover, si.shortInterest, si.settlementDate)
                    .trim(),
        )
    }

class Scanner(
    private val provider: MarketDataProvider,
    private val criteria: ScanCriteria = ScanCriteria(),
) {
    /** Fetch top gainers, reduce them to the day's universe, then apply the squeeze guard. */
    suspend fun scan(): List<WatchlistItem> {
        val candidates = filterCandidates(provider.getGainers(), criteria)
        if (candidates.isEmpty()) return candidates
        // Short interest must never sink a scan: on failure we trade on the gainers alone.
        val shortInterest =
            runCatching { provider.getShortInterest(candidates.map { it.ticker }) }
                .onFailure {
                    println("${Instant.now()} short interest unavailable (${it.message}) — scanning without the squeeze guard")
                }.getOrDefault(emptyMap())
        return applyShortInterest(candidates, shortInterest, criteria)
    }
}
