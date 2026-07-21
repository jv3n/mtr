package mtr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * TradeZero connector — official API (REST). Execution + locates + P&L only;
 * TradeZero has NO market data (see MarketData.kt / Massive).
 *
 * Auth: header-based, no HMAC/OAuth. TZ-API-KEY-ID + TZ-API-SECRET-KEY on every
 * request; the key pair selects paper vs live.
 *
 * All field names/enums below are confirmed against the paper API (see the old
 * Python smoke/order tests): account id field is `account`; responses are wrapped
 * ({"accounts":[...]}, {"positions":[...]}); a short is Sell + openClose Open;
 * enums are capitalized; quantity is `orderQuantity`; route is PAPER/SMART.
 */
class TradeZeroConnector(
    private val creds: TradeZeroCredentials,
    baseUrl: String = "https://webapi.tradezero.com",
) {
    private val base = baseUrl.trimEnd('/') + "/v1/api"
    private val client =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            defaultRequest {
                header("TZ-API-KEY-ID", creds.apiKey)
                header("TZ-API-SECRET-KEY", creds.apiSecret)
                accept(ContentType.Application.Json)
            }
        }

    fun close() = client.close()

    private val defaultRoute: String
        get() = if (creds.environment == "paper") "PAPER" else "SMART"

    // --- Account / positions / P&L ---
    suspend fun listAccounts(): List<JsonObject> {
        val body = client.get("$base/accounts").body<JsonObject>()
        return body["accounts"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    /** The account id is the `account` field (e.g. "TZP8B199"). */
    suspend fun accountId(): String =
        listAccounts()
            .firstOrNull()
            ?.get("account")
            ?.jsonPrimitive
            ?.content
            ?: error("no TradeZero account found")

    suspend fun getPositions(accountId: String): List<JsonObject> {
        val body = client.get("$base/accounts/$accountId/positions").body<JsonObject>()
        return body["positions"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    suspend fun getPnl(accountId: String): JsonObject = client.get("$base/accounts/$accountId/pnl").body()

    // --- Short eligibility & locates (critical path) ---
    suspend fun isEasyToBorrow(
        accountId: String,
        symbol: String,
    ): Boolean =
        client
            .get("$base/accounts/$accountId/is-easy-to-borrow/symbol/$symbol")
            .body<JsonObject>()["isEasyToBorrow"]
            ?.jsonPrimitive
            ?.booleanOrNull ?: false

    /**
     * Initiate a short locate quote. ASYNCHRONOUS: the REST response is only an
     * ack ({"locateQuoteSent":"true"}); the real quote (cost/availability) arrives
     * over the WebSocket stream (to implement). Returns true if the quote was sent.
     */
    suspend fun requestLocate(
        accountId: String,
        symbol: String,
        shares: Int,
    ): Boolean {
        val resp =
            client
                .post("$base/accounts/locates/quote") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("account", accountId)
                            put("symbol", symbol)
                            put("quantity", shares)
                        },
                    )
                }.body<JsonObject>()
        return resp["locateQuoteSent"]?.jsonPrimitive?.content?.lowercase() == "true"
    }

    // --- Execution ---
    private suspend fun submitOrder(
        accountId: String,
        symbol: String,
        side: String,
        openClose: String,
        shares: Int,
        orderType: String,
        limitPrice: Double?,
        route: String?,
        clientOrderIdPrefix: String,
    ): OrderResult {
        val clientOrderId = "$clientOrderIdPrefix-${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val payload =
            buildJsonObject {
                put("account", accountId)
                put("securityType", "Stock")
                put("symbol", symbol)
                put("side", side)
                put("openClose", openClose)
                put("orderQuantity", shares)
                put("orderType", orderType)
                put("timeInForce", "Day")
                put("route", route ?: defaultRoute)
                put("clientOrderId", clientOrderId)
                if (limitPrice != null) put("limitPrice", limitPrice)
            }
        val resp =
            client.post("$base/accounts/$accountId/order") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        val accepted = resp.status.value in 200..299
        return OrderResult(orderId = clientOrderId, ticker = symbol, accepted = accepted)
    }

    /** Short = Sell + openClose Open. Never call without a secured locate for HTB names. */
    suspend fun submitShort(
        accountId: String,
        symbol: String,
        shares: Int,
        orderType: String = "Market",
        limitPrice: Double? = null,
        route: String? = null,
    ): OrderResult = submitOrder(accountId, symbol, "Sell", "Open", shares, orderType, limitPrice, route, "mtr")

    /** Net share count for a symbol across all position lines (negative = short). */
    suspend fun netShares(
        accountId: String,
        symbol: String,
    ): Double =
        getPositions(accountId)
            .filter { it["symbol"]?.jsonPrimitive?.content == symbol }
            .sumOf { it["shares"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }

    /** Close the open position for a symbol (cover a short / sell a long), sized from the net position. */
    suspend fun flatten(
        accountId: String,
        symbol: String,
    ): OrderResult {
        val net = netShares(accountId, symbol)
        if (net == 0.0) return OrderResult("", symbol, false, "no open position")
        val side = if (net < 0) "Buy" else "Sell"
        return submitOrder(accountId, symbol, side, "Close", kotlin.math.abs(net).toInt(), "Market", null, null, "mtr-flat")
    }
}

data class OrderResult(
    val orderId: String,
    val ticker: String,
    val accepted: Boolean,
    val reason: String = "",
)
