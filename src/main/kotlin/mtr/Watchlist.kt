package mtr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loading and validation of the day's watchlist (~10 tickers).
 * Only loads + validates — makes no trading decision.
 */
data class WatchlistItem(
    val ticker: String,
    val note: String? = null,
    val maxShortSizeUsd: Double? = null,
)

object Watchlist {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Expected format:
     *   {"tickers": [{"ticker": "ABCD", "note": "...", "max_short_size_usd": 1500}, ...]}
     * or short form: {"tickers": ["ABCD", "WXYZ"]}
     */
    fun load(path: Path): List<WatchlistItem> {
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val entries =
            root["tickers"]?.jsonArray
                ?: throw IllegalArgumentException("invalid watchlist: 'tickers' key missing")
        require(entries.isNotEmpty()) { "invalid watchlist: 'tickers' is empty" }

        val seen = mutableSetOf<String>()
        return entries.map { e ->
            val obj: JsonObject = if (e is JsonObject) e else buildJsonObject { put("ticker", e.jsonPrimitive.content) }
            val ticker =
                obj["ticker"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.uppercase()
                    .orEmpty()
            require(ticker.isNotEmpty()) { "entry without ticker: $e" }
            require(seen.add(ticker)) { "duplicate ticker: $ticker" }
            WatchlistItem(
                ticker = ticker,
                note = obj["note"]?.jsonPrimitive?.contentOrNull,
                maxShortSizeUsd = obj["max_short_size_usd"]?.jsonPrimitive?.doubleOrNull,
            )
        }
    }
}
