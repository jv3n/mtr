package mtr

import kotlin.math.abs

/**
 * Guardrails — NOT optional (see §6 of docs/start.md).
 * Every decision to place an order must go through the RiskManager.
 */
class RiskManager(
    private val limits: RiskLimits,
) {
    var realizedPnlUsd: Double = 0.0
        private set
    var grossExposureUsd: Double = 0.0
        private set
    val openPositions: MutableMap<String, Double> = mutableMapOf() // ticker -> USD notional
    var halted: Boolean = false
        private set

    // Round-trips closed today. Relevant for the PDT rule (>=4 day trades / 5 days needs >=$25k).
    var dayTrades: Int = 0
        private set

    /** Kill switch — blocks any new risk-taking. */
    fun kill() {
        halted = true
    }

    fun recordDayTrade() {
        dayTrades++
    }

    /** Returns (allowed, reason). The reason is useful for logging/alerting. */
    fun canOpenShort(
        ticker: String,
        notionalUsd: Double,
    ): Pair<Boolean, String> {
        if (halted) return false to "kill switch active"
        if (realizedPnlUsd <= -limits.maxDailyLossUsd) {
            return false to "daily loss limit reached (%.0f)".format(realizedPnlUsd)
        }
        if (notionalUsd > limits.maxPositionUsd) {
            return false to "position size %.0f > max %.0f".format(notionalUsd, limits.maxPositionUsd)
        }
        if (grossExposureUsd + notionalUsd > limits.maxGrossExposureUsd) {
            return false to "max gross exposure exceeded"
        }
        if (ticker !in openPositions && openPositions.size >= limits.maxPositions) {
            return false to "max number of positions reached (${limits.maxPositions})"
        }
        return true to "ok"
    }

    fun registerFill(
        ticker: String,
        notionalUsd: Double,
    ) {
        openPositions[ticker] = (openPositions[ticker] ?: 0.0) + notionalUsd
        grossExposureUsd += abs(notionalUsd)
    }

    fun closePosition(ticker: String) {
        val notional = openPositions.remove(ticker) ?: 0.0
        grossExposureUsd -= abs(notional)
    }

    fun registerRealizedPnl(pnlUsd: Double) {
        realizedPnlUsd += pnlUsd
        if (realizedPnlUsd <= -limits.maxDailyLossUsd) kill()
    }
}
