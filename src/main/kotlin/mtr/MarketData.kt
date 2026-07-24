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
import io.ktor.client.request.parameter
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
import java.time.LocalDate

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

/** Static/reference data for one ticker. */
data class TickerReference(
    val ticker: String,
    /**
     * ⚠️ SHARES OUTSTANDING, **not** the free float — Massive publishes no float.
     *
     * Outstanding ≥ float always, so this is an UPPER BOUND and nothing more: a low value
     * rules a ticker out for certain, a high one does NOT rule it in. GUS reads the real
     * float off Yahoo Statistics (`docs/pattern/gus.md`), which we have no feed for.
     */
    val sharesOutstanding: Long? = null,
    val marketCapUsd: Double? = null,
)

/** A one-shot market snapshot for one ticker (used by the scanner). */
data class MarketSnapshot(
    val ticker: String,
    val price: Double,
    val dayChangePct: Double, // fraction vs the previous close: 0.25 = +25% ON THE DAY
    val volume: Long,
    val dayOpen: Double = 0.0, // 0 until the session opens (pre-market scan)
    val prevClose: Double = 0.0,
) {
    /**
     * GUS criterion #2 — the overnight gap: `(open − previous close) / previous close`.
     *
     * This is NOT [dayChangePct] (current price vs previous close) and NOT the move since
     * the open. On a gap-and-fade — the setup we short — the three diverge hard: a ticker
     * can gap +80 %, fade all morning, and show a *negative* move since the open while the
     * gap that qualifies it stays +80 %.
     *
     * Before the open there is no `day.o` yet, so we fall back to the last pre-market
     * print, which is what a 7 a.m. scan can actually see; [gapIsProvisional] flags it,
     * since the real gap is only fixed at 9:30.
     */
    val gapPct: Double
        get() =
            when {
                prevClose <= 0.0 -> 0.0
                dayOpen > 0.0 -> (dayOpen - prevClose) / prevClose
                else -> (price - prevClose) / prevClose
            }

    /** True when [gapPct] comes from a pre-market print rather than the official open. */
    val gapIsProvisional: Boolean get() = prevClose > 0.0 && dayOpen <= 0.0
}

/**
 * FINRA short interest for one ticker, as of [settlementDate].
 *
 * Reported on a two-week cadence, so this is CONTEXT (squeeze-risk filtering and sizing),
 * never an entry signal. [daysToCover] = shortInterest / avgDailyVolume: the higher it is,
 * the more crowded the short side and the greater the squeeze risk — the main danger of
 * this strategy.
 */
data class ShortInterest(
    val ticker: String,
    val shortInterest: Long,
    val daysToCover: Double,
    val avgDailyVolume: Long,
    val settlementDate: String,
)

interface MarketDataProvider {
    suspend fun getReference(ticker: String): TickerReference

    /** Top % gainers of the day (drives the autonomous scanner). Paid tier on Massive. */
    suspend fun getGainers(): List<MarketSnapshot>

    /**
     * Latest short interest for each requested ticker, keyed by ticker.
     * Tickers with no FINRA coverage are simply absent from the map.
     *
     * Included in every Massive plan (Basic free tier too) — see
     * `docs/market-data-benchmark.md`.
     */
    suspend fun getShortInterest(tickers: List<String>): Map<String, ShortInterest>

    /**
     * GUS criterion #7 — tickers among [tickers] that ran a reverse split on or after
     * [since]. Returns only the offenders, so an empty set means "none of them did".
     */
    suspend fun getRecentReverseSplits(
        tickers: List<String>,
        since: LocalDate,
    ): Set<String>

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
            sharesOutstanding = shares,
            marketCapUsd = res?.get("market_cap")?.jsonPrimitive?.doubleOrNull,
        )
    }

    override suspend fun getGainers(): List<MarketSnapshot> {
        val tickers =
            client
                .get("$MASSIVE_REST_BASE/v2/snapshot/locale/us/markets/stocks/gainers")
                .body<JsonObject>()["tickers"]
                ?.jsonArray
                .orEmpty()
        return tickers.mapNotNull { el ->
            val t = el.jsonObject
            val ticker = t["ticker"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val day = t["day"]?.jsonObject
            val price =
                (t["lastTrade"]?.jsonObject?.get("p") ?: day?.get("c"))?.jsonPrimitive?.doubleOrNull ?: 0.0
            val volume = day?.get("v")?.jsonPrimitive?.longOrNull ?: 0L
            val changePct = (t["todaysChangePerc"]?.jsonPrimitive?.doubleOrNull ?: 0.0) / 100.0
            // day.o and prevDay.c are already in this payload -> the gap costs no extra call.
            MarketSnapshot(
                ticker = ticker,
                price = price,
                dayChangePct = changePct,
                volume = volume,
                dayOpen = day?.get("o")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                prevClose =
                    t["prevDay"]
                        ?.jsonObject
                        ?.get("c")
                        ?.jsonPrimitive
                        ?.doubleOrNull ?: 0.0,
            )
        }
    }

    /**
     * One batched request (`ticker.any_of` + `execution_date.gte`) for the whole universe —
     * the Basic plan allows only 5 requests/minute.
     *
     * A reverse split is `split_to < split_from` (a 1-for-10 comes back as from=10, to=1).
     * That ratio IS the definition, so it decides; `adjustment_type` is only the fallback
     * for a row that omits the ratio.
     */
    override suspend fun getRecentReverseSplits(
        tickers: List<String>,
        since: LocalDate,
    ): Set<String> {
        if (tickers.isEmpty()) return emptySet()
        val results =
            client
                .get("$MASSIVE_REST_BASE/stocks/v1/splits") {
                    parameter("ticker.any_of", tickers.joinToString(",") { it.uppercase() })
                    parameter("execution_date.gte", since.toString())
                    parameter("limit", 1000)
                }.body<JsonObject>()["results"]
                ?.jsonArray
                .orEmpty()
        return results
            .mapNotNull { el ->
                val o = el.jsonObject
                val t = o["ticker"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val from = o["split_from"]?.jsonPrimitive?.doubleOrNull
                val to = o["split_to"]?.jsonPrimitive?.doubleOrNull
                val reverse =
                    if (from != null && to != null) {
                        to < from
                    } else {
                        o["adjustment_type"]?.jsonPrimitive?.content == "reverse_split"
                    }
                t.takeIf { reverse }
            }.toSet()
    }

    /**
     * One request for the whole batch (`ticker.any_of`) — the Basic plan allows only
     * 5 requests/minute, so querying ticker by ticker would blow the quota on a full scan.
     *
     * The endpoint returns the FULL history, several settlement dates per ticker; sorting
     * by settlement_date desc means the first row seen for a ticker is its latest one.
     */
    override suspend fun getShortInterest(tickers: List<String>): Map<String, ShortInterest> {
        if (tickers.isEmpty()) return emptyMap()
        val results =
            client
                .get("$MASSIVE_REST_BASE/stocks/v1/short-interest") {
                    parameter("ticker.any_of", tickers.joinToString(",") { it.uppercase() })
                    parameter("sort", "settlement_date.desc")
                    // Room for several settlement dates per ticker, so none is missed.
                    parameter("limit", tickers.size * 8)
                }.body<JsonObject>()["results"]
                ?.jsonArray
                .orEmpty()
        return results
            .mapNotNull { el ->
                val o = el.jsonObject
                val t = o["ticker"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ShortInterest(
                    ticker = t,
                    shortInterest = o["short_interest"]?.jsonPrimitive?.longOrNull ?: 0L,
                    daysToCover = o["days_to_cover"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    avgDailyVolume = o["avg_daily_volume"]?.jsonPrimitive?.longOrNull ?: 0L,
                    settlementDate = o["settlement_date"]?.jsonPrimitive?.content.orEmpty(),
                )
            }.groupBy { it.ticker }
            .mapValues { (_, rows) -> rows.first() }
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
