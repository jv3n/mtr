package mtr

import java.time.Instant
import java.time.LocalDate

/**
 * Autonomous universe scanner (roadmap #5), aligned on the GUS pattern (#15).
 *
 * Picks the day's tradeable small-cap pump candidates from Massive's top gainers,
 * so the bot chooses its own tickers — no manual watchlist.
 *
 * Every filtering step is PURE (no I/O) → unit-testable; [Scanner.scan] only wires the
 * I/O around them.
 */
data class ScanCriteria(
    /** GUS #1 — under $10 the big funds stay out; under $1 it is not listable. */
    val minPrice: Double = 1.0,
    val maxPrice: Double = 10.0,
    /**
     * GUS #2 — the overnight gap, and the single most important criterion per the pattern:
     * it is what leaves enough room below for the short to pay. 0.70 in a strong market.
     *
     * Distinct from [minGainPct]: this is measured at the open, that one is "right now".
     */
    val minGapPct: Double = 0.50,
    /**
     * Secondary sanity check: still up on the day at scan time. A ticker that gapped +80 %
     * and has already given it all back is no longer the setup.
     */
    val minGainPct: Double = 0.20,
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
    /**
     * GUS #3 — float 3–50 M, approximated by SHARES OUTSTANDING (see [TickerReference]).
     *
     * The lower bound is safe: outstanding ≥ float, so under 3 M outstanding the float is
     * under 3 M for certain. The upper bound is NOT safe the same way — a ticker with 80 M
     * outstanding can easily float under 50 M — so the pattern's 50 M ceiling is doubled
     * here. That deliberately lets some genuinely-too-large names through, which costs a
     * bad candidate; enforcing 50 M on the wrong quantity would silently drop valid setups,
     * which costs the edge. Null on either bound disables that side.
     */
    val minSharesOutstanding: Long? = 3_000_000,
    val maxSharesOutstanding: Long? = 100_000_000,
    /** GUS #7 — drop tickers that ran a reverse split within this window. Null = no check. */
    val reverseSplitLookbackDays: Long? = 365,
)

/**
 * Filter + rank gainer snapshots into the day's universe. Pure.
 *
 * Ranked by GAP descending, not by current change: the pattern says to take the 3–4
 * biggest overnight gappers.
 */
fun filterCandidates(
    snapshots: List<MarketSnapshot>,
    criteria: ScanCriteria = ScanCriteria(),
): List<WatchlistItem> =
    snapshots
        .filter { it.price in criteria.minPrice..criteria.maxPrice }
        .filter { it.gapPct >= criteria.minGapPct }
        .filter { it.dayChangePct >= criteria.minGainPct }
        .filter { it.volume >= criteria.minVolume }
        .sortedByDescending { it.gapPct }
        .take(criteria.maxResults)
        .map {
            WatchlistItem(
                ticker = it.ticker,
                note =
                    "gap +%.0f%%%s, +%.0f%% today, vol %d".format(
                        it.gapPct * 100,
                        if (it.gapIsProvisional) " (pre-market)" else "",
                        it.dayChangePct * 100,
                        it.volume,
                    ),
                gapPct = it.gapPct,
            )
        }

/**
 * GUS #3, float band. Annotates each candidate with its shares outstanding and drops those
 * outside the configured bounds. Pure (no I/O) → unit-testable.
 *
 * Tickers with no reference data are KEPT and marked `float n/a`, same rule as the squeeze
 * guard: the filter acts on data it has, never on data it lacks. The note says `~` because
 * the number is outstanding, not float.
 */
fun applyFloatProxy(
    candidates: List<WatchlistItem>,
    references: Map<String, TickerReference>,
    criteria: ScanCriteria = ScanCriteria(),
): List<WatchlistItem> =
    candidates.mapNotNull { item ->
        val shares =
            references[item.ticker]?.sharesOutstanding
                ?: return@mapNotNull item.copy(note = "${item.note.orEmpty()} · float n/a".trim())
        if (criteria.minSharesOutstanding != null && shares < criteria.minSharesOutstanding) return@mapNotNull null
        if (criteria.maxSharesOutstanding != null && shares > criteria.maxSharesOutstanding) return@mapNotNull null
        item.copy(note = "%s · float ~%,d".format(item.note.orEmpty(), shares).trim())
    }

/**
 * GUS #7. Drops any candidate that ran a reverse split recently — a consolidation creates
 * no value for the holder and distorts the price history the rest of the pattern reads.
 * Pure.
 */
fun applyReverseSplitFilter(
    candidates: List<WatchlistItem>,
    reverseSplitTickers: Set<String>,
): List<WatchlistItem> = candidates.filterNot { it.ticker in reverseSplitTickers }

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
    /**
     * Gainers → gap/price/volume band → float band → reverse splits → squeeze guard.
     *
     * Every enrichment step is best-effort: a data source that fails degrades the scan to
     * the filters that did work rather than sinking it, and says so in the log.
     */
    suspend fun scan(): List<WatchlistItem> {
        var candidates = filterCandidates(provider.getGainers(), criteria)
        if (candidates.isEmpty()) return candidates

        candidates = applyFloatProxy(candidates, fetchReferences(candidates), criteria)
        if (candidates.isEmpty()) {
            log("every candidate was outside the float band")
            return candidates
        }

        candidates = applyReverseSplitFilter(candidates, fetchReverseSplits(candidates))
        if (candidates.isEmpty()) {
            log("every candidate had a recent reverse split")
            return candidates
        }

        // Short interest must never sink a scan: on failure we trade on the gainers alone.
        val shortInterest =
            runCatching { provider.getShortInterest(candidates.map { it.ticker }) }
                .onFailure { log("short interest unavailable (${it.message}) — scanning without the squeeze guard") }
                .getOrDefault(emptyMap())
        return applyShortInterest(candidates, shortInterest, criteria)
    }

    /** One call per ticker (Massive has no batched ticker-details endpoint), failures → unknown. */
    private suspend fun fetchReferences(candidates: List<WatchlistItem>): Map<String, TickerReference> =
        candidates
            .mapNotNull { item ->
                runCatching { provider.getReference(item.ticker) }
                    .onFailure { log("reference data unavailable for ${item.ticker} (${it.message}) — treated as unknown float") }
                    .getOrNull()
            }.associateBy { it.ticker }

    private suspend fun fetchReverseSplits(candidates: List<WatchlistItem>): Set<String> {
        val lookback = criteria.reverseSplitLookbackDays ?: return emptySet()
        val since = LocalDate.now().minusDays(lookback)
        return runCatching { provider.getRecentReverseSplits(candidates.map { it.ticker }, since) }
            .onFailure { log("splits unavailable (${it.message}) — scanning without the reverse-split filter") }
            .getOrDefault(emptySet())
    }

    private fun log(msg: String) = println("${Instant.now()} scanner: $msg")
}
