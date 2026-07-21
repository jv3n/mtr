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

data class TradeZeroCredentials(
    val apiKey: String,
    val apiSecret: String,
    // "paper" or "live". Always start in paper.
    val environment: String = "paper",
) {
    companion object {
        fun fromEnv(): TradeZeroCredentials {
            val key = envOrNull("TRADEZERO_API_KEY")
            val secret = envOrNull("TRADEZERO_API_SECRET")
            val env = envOrNull("TRADEZERO_ENV") ?: "paper"
            require(!key.isNullOrBlank() && !secret.isNullOrBlank()) {
                "TRADEZERO_API_KEY / TRADEZERO_API_SECRET are missing. " +
                    "Set them in .env (local) or via Secret Manager (production)."
            }
            return TradeZeroCredentials(key, secret, env)
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
