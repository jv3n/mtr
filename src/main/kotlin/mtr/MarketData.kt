package mtr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Market data provider — SEPARATE from TradeZero (which has no market data).
 * Chosen provider: Massive (formerly Polygon.io, rebranded 2025-10-30).
 *
 * NB: free/basic tier is 15-min delayed / EOD (no real-time) → live intraday
 * detection needs a paid real-time Stocks plan. Point MASSIVE_WS_URL at the
 * delayed host for dev.
 */
data class Quote(
    val ticker: String,
    val price: Double,
    val volume: Long, // accumulated day volume
    val vwap: Double,
    val dayOpen: Double,
    val dayHigh: Double,
)

/** Static/reference data (float proxy, market cap) for the watchlist UI. */
data class TickerReference(
    val ticker: String,
    val floatShares: Long? = null,
    val marketCapUsd: Double? = null,
)

interface MarketDataProvider {
    suspend fun getReference(ticker: String): TickerReference

    fun streamQuotes(tickers: List<String>): Flow<Quote>
}

class MassiveProvider(
    private val apiKey: String,
    private val wsUrl: String = MASSIVE_WS_REALTIME,
) : MarketDataProvider {
    companion object {
        // New hostnames (old api.polygon.io / socket.polygon.io still work for a while).
        const val MASSIVE_WS_REALTIME = "wss://socket.massive.com/stocks"
        const val MASSIVE_REST_BASE = "https://api.massive.com"

        fun fromEnv(): MassiveProvider {
            val key = envOrNull("MASSIVE_API_KEY") ?: envOrNull("POLYGON_API_KEY")
            requireNotNull(key) { "MASSIVE_API_KEY (or legacy POLYGON_API_KEY) is missing. Set it in .env or Secret Manager." }
            val ws = envOrNull("MASSIVE_WS_URL") ?: MASSIVE_WS_REALTIME
            return MassiveProvider(key, ws)
        }
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // Bearer header instead of ?apiKey= so the key never leaks into URLs/logs.
    private val client =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
            defaultRequest { header(HttpHeaders.Authorization, "Bearer $apiKey") }
        }

    fun close() = client.close()

    override suspend fun getReference(ticker: String): TickerReference {
        val res =
            client
                .get("$MASSIVE_REST_BASE/v3/reference/tickers/$ticker")
                .body<JsonObject>()["results"]
                ?.jsonObject
        val shares =
            res?.get("share_class_shares_outstanding")?.jsonPrimitive?.longOrNull
                ?: res?.get("weighted_shares_outstanding")?.jsonPrimitive?.longOrNull
        return TickerReference(
            ticker = ticker,
            floatShares = shares,
            marketCapUsd = res?.get("market_cap")?.jsonPrimitive?.doubleOrNull,
        )
    }

    /**
     * Real-time per-second aggregates (A.*), which carry the day's VWAP (`a`) and
     * accumulated volume (`av`). Flow: connect → auth → subscribe → parse events.
     */
    override fun streamQuotes(tickers: List<String>): Flow<Quote> =
        flow {
            val params = tickers.joinToString(",") { "A.${it.uppercase()}" }
            client.webSocket(wsUrl) {
                send(Frame.Text("""{"action":"auth","params":"$apiKey"}"""))
                send(Frame.Text("""{"action":"subscribe","params":"$params"}"""))
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    for (el in json.parseToJsonElement(frame.readText()).jsonArray) {
                        val m = el.jsonObject
                        if (m["ev"]?.jsonPrimitive?.content != "A") continue
                        val sym = m["sym"]?.jsonPrimitive?.content ?: continue
                        emit(
                            Quote(
                                ticker = sym,
                                price = m["c"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                                volume = m["av"]?.jsonPrimitive?.longOrNull ?: 0L,
                                vwap = m["a"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                                dayOpen = m["op"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                                dayHigh = m["h"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            ),
                        )
                    }
                }
            }
        }
}
