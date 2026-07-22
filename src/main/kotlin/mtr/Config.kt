package mtr

import io.github.cdimascio.dotenv.dotenv

/**
 * Configuration and secrets.
 *
 * Secrets (API keys) are NEVER hardcoded or committed.
 * - Local dev: a `.env` file (git-ignored), loaded here.
 * - Production (GCP): real environment variables from Secret Manager.
 */

private val dotenv by lazy {
    // ignoreIfMissing: prod has no .env, it uses real env vars.
    dotenv { ignoreIfMissing = true }
}

/** Look up a key from .env first, then the process environment. */
fun envOrNull(key: String): String? = dotenv[key] ?: System.getenv(key)

/**
 * Trading environment. More than a label: routes, locate requirements and the safety gates
 * on destructive scripts all hang off it, and a different broker would express its own
 * quirks the same way. Keep behavioural differences HERE rather than scattering
 * `== "paper"` string tests across the codebase.
 */
enum class TradingEnv {
    PAPER,
    LIVE,
    ;

    val isLive: Boolean get() = this == LIVE

    /** Paper only has simulated venues; live routes to the real ones. */
    val defaultRoute: String get() = if (this == PAPER) "PAPER" else "SMART"

    /**
     * Whether shorting a hard-to-borrow name requires securing a locate first.
     *
     * TradeZero locates are LIVE-ONLY (paper locate calls come back Rejected), and paper
     * accepts a short with no locate at all — verified 2026-07-22, see issue #4.
     */
    val requiresLocateForHardToBorrow: Boolean get() = this == LIVE

    /** Lowercase, so log lines and alerts read the way they always have. */
    override fun toString(): String = name.lowercase()

    companion object {
        /**
         * Anything not explicitly live resolves to PAPER. A typo in `TRADEZERO_ENV` must
         * never be what puts real money at risk.
         */
        fun parse(raw: String?): TradingEnv =
            when (raw?.trim()?.lowercase()) {
                "live", "prod", "production", "real" -> LIVE
                else -> PAPER
            }
    }
}

data class TradeZeroCredentials(
    val apiKey: String,
    val apiSecret: String,
    // Always start in paper.
    val environment: TradingEnv = TradingEnv.PAPER,
) {
    companion object {
        fun fromEnv(): TradeZeroCredentials {
            val key = envOrNull("TRADEZERO_API_KEY")
            val secret = envOrNull("TRADEZERO_API_SECRET")
            require(!key.isNullOrBlank() && !secret.isNullOrBlank()) {
                "TRADEZERO_API_KEY / TRADEZERO_API_SECRET are missing. " +
                    "Set them in .env (local) or via Secret Manager (production)."
            }
            return TradeZeroCredentials(key, secret, TradingEnv.parse(envOrNull("TRADEZERO_ENV")))
        }
    }
}

/** Guardrails — see Risk.kt. Conservative defaults. */
data class RiskLimits(
    val maxDailyLossUsd: Double = 500.0,
    val maxPositionUsd: Double = 2000.0,
    val maxGrossExposureUsd: Double = 6000.0,
    val maxPositions: Int = 5,
)
