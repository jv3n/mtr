package mtr

import java.util.Locale

/*
 * Telegram signal formatting (signal mode).
 *
 * Since the bot cannot yet trade for real, it also acts as an advisor: it scans, detects
 * the GUS setup live, and pushes a human-readable signal so the trade can be judged by hand.
 * These builders are PURE (no I/O) — the exact text is unit-testable without any network,
 * like the rest of the strategy maths (evaluate, shouldExit).
 *
 * Only the GUS entry is wired here; the Double Top trigger is deferred to #17.
 */

/**
 * Digest of the day's universe, sent once at startup. Lists each candidate with the note the
 * scanner attached (gap, float, days-to-cover, SSR — see [Scanner]). Empty universe → a short
 * "no candidate" line rather than an empty message.
 */
fun formatScanDigest(universe: List<WatchlistItem>): String {
    if (universe.isEmpty()) return "📋 Univers du jour — aucun candidat GUS."
    val lines =
        universe.joinToString("\n") { item ->
            val note = item.note?.let { " — $it" }.orEmpty()
            "• ${item.ticker}$note"
        }
    return "📋 Univers du jour — ${universe.size} candidat(s) GUS :\n$lines"
}

/**
 * Entry signal for a fired GUS short. The suggested stop and target come straight from the
 * Double Top position rules ([StrategyParams.stopLossPct]/[StrategyParams.takeProfitPct]),
 * so the alert carries the very plan the bot would manage: for a short the stop sits ABOVE
 * the entry (a rising price is the loss) and the target BELOW it.
 *
 * [shares] is the risk-derived size ([shareCount]), already halved when the name is under
 * SSR; the SSR line only flags why the size is smaller.
 */
fun formatEntrySignal(
    state: TickerState,
    signal: Signal,
    shares: Int,
    params: StrategyParams = StrategyParams(),
): String {
    val entry = state.lastPrice
    val stop = entry * (1 + params.stopLossPct)
    val target = entry * (1 - params.takeProfitPct)
    return buildString {
        append("📉 SIGNAL SHORT ${state.ticker} — GUS\n")
        append(signal.reason).append('\n')
        append(
            String.format(
                Locale.US,
                "entrée ~%.2f\$ · stop %.2f\$ (+%.0f%%) · TP %.2f\$ (-%.0f%%) · ~%d sh (~%.0f\$)",
                entry,
                stop,
                params.stopLossPct * 100,
                target,
                params.takeProfitPct * 100,
                shares,
                shares * entry,
            ),
        )
        if (state.ssrActive) append("\n⚠️ SSR — shortable à l'uptick seulement, taille réduite")
    }
}
