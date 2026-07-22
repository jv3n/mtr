package mtr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** A non-2xx answer from TradeZero, carrying the broker's own message (e.g. "R114: ..."). */
class TradeZeroApiException(
    val status: Int,
    val apiMessage: String,
) : RuntimeException("TradeZero HTTP $status: $apiMessage")

/**
 * A locate quote as reported by `GET /accounts/:id/locates/history`.
 *
 * Probed live on 2026-07-22 (paper) — see issue #4. Quotes expire about 30 seconds after
 * creation, so a decision has to be made quickly.
 */
data class LocateQuote(
    val accountId: String,
    val symbol: String,
    val shares: Int,
    val pricePerShare: Double,
    val status: Int,
    /** Null when the API leaves it unset — paper returns the literal `<no value>`. */
    val quoteReqId: String?,
    val filledShares: Int,
    val error: Int,
    val text: String,
    val createdDate: String,
    val updatedDate: String,
) {
    /** What accepting this locate would cost. */
    val totalCostUsd: Double get() = pricePerShare * shares

    val isAvailable: Boolean get() = status == STATUS_AVAILABLE
    val isExpired: Boolean get() = status == STATUS_EXPIRED
    val isError: Boolean get() = status == STATUS_ERROR || error != 0

    /** True once the broker has answered, whatever the answer. */
    val isSettled: Boolean get() = isAvailable || isExpired || isError

    /** Acceptable only if the broker granted it AND gave us an id to accept with. */
    val isAcceptable: Boolean get() = isAvailable && !quoteReqId.isNullOrBlank()

    companion object {
        // Status codes observed empirically; TradeZero does not document them.
        const val STATUS_AVAILABLE = 65
        const val STATUS_EXPIRED = 67
        const val STATUS_ERROR = 56
    }
}

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
 *
 * Locates: `quote` and `accept` are account-less paths, but `inventory` and `history`
 * are scoped by account (`/accounts/:id/locates/...`) — the un-scoped forms 404.
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
            // TradeZero gzips its ERROR bodies but not its 200s: without this, any 4xx blew
            // up as "Expected start of the object '{'" instead of surfacing the real message.
            install(ContentEncoding) {
                gzip()
                deflate()
            }
            defaultRequest {
                header("TZ-API-KEY-ID", creds.apiKey)
                header("TZ-API-SECRET-KEY", creds.apiSecret)
                accept(ContentType.Application.Json)
            }
        }

    /**
     * Parse a JSON body, or throw a readable [TradeZeroApiException] carrying the API's own
     * message. Never let a non-2xx surface as a deserialization error — an order-path caller
     * needs to distinguish "broker refused" from "we failed to read the answer".
     */
    private suspend fun jsonOrThrow(resp: HttpResponse): JsonObject {
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw TradeZeroApiException(resp.status.value, extractMessage(text))
        }
        return runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw TradeZeroApiException(resp.status.value, "unparseable body: ${text.take(200)}") }
    }

    /** TradeZero nests its real message in a JSON-encoded `message` field. Unwrap one level. */
    private fun extractMessage(body: String): String {
        val outer = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return body.take(200)
        val message = outer["message"]?.jsonPrimitive?.contentOrNull ?: return body.take(200)
        val inner = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull()
        return inner?.get("message")?.jsonPrimitive?.contentOrNull ?: message.trim()
    }

    fun close() = client.close()

    /** Exposed so callers can branch on environment without carrying credentials around. */
    val environment: TradingEnv get() = creds.environment

    private val defaultRoute: String
        get() = creds.environment.defaultRoute

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
     * Initiate a short locate quote. ASYNCHRONOUS: the REST response is only an ack
     * ({"locateQuoteSent":"true"}). Returns true if the request was accepted.
     *
     * The resulting quote does NOT arrive over the WebSocket — contrary to the original
     * assumption, it lands in [getLocateHistory] a couple of seconds later. Probed live on
     * 2026-07-22, see issue #4. Use [awaitLocateQuote] to do both steps.
     *
     * Sends a fresh `clientOrderId` on every call: TradeZero consumes one PERMANENTLY on
     * acceptance, and reuse yields `R114: Invalid duplicate UserOrderId` forever after.
     *
     * ⚠️ On PAPER this still fails with R114 from the second call onwards, whatever
     * clientOrderId we send — verified 2026-07-22. Locates are documented as **live-only**
     * (paper locate calls return Rejected rows), so this is expected, not a bug to chase.
     */
    suspend fun requestLocate(
        accountId: String,
        symbol: String,
        shares: Int,
    ): Boolean {
        val resp =
            client.post("$base/accounts/locates/quote") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("account", accountId)
                        put("symbol", symbol)
                        put("quantity", shares)
                        put("clientOrderId", newClientOrderId("loc"))
                    },
                )
            }
        return jsonOrThrow(resp)["locateQuoteSent"]?.jsonPrimitive?.content?.lowercase() == "true"
    }

    /** Locate quotes and their outcomes, most recent first. */
    suspend fun getLocateHistory(accountId: String): List<LocateQuote> =
        client
            .get("$base/accounts/$accountId/locates/history")
            .body<JsonObject>()["locateHistory"]
            ?.jsonArray
            ?.map { parseLocateQuote(it.jsonObject) }
            .orEmpty()

    /**
     * Locates already secured for the account. Shape unknown: it was empty on every probe,
     * so callers get the raw objects rather than a model invented from nothing.
     */
    suspend fun getLocateInventory(accountId: String): List<JsonObject> =
        client
            .get("$base/accounts/$accountId/locates/inventory")
            .body<JsonObject>()["locateInventory"]
            ?.jsonArray
            ?.map { it.jsonObject }
            .orEmpty()

    /**
     * Accept a pending locate quote, committing to its cost.
     *
     * ⚠️ The payload field is `accountId` here, NOT `account` as in [requestLocate] — the
     * API is inconsistent on this. Confirmed via its own validation errors.
     *
     * ⚠️ The response is only an ack: the endpoint answers 200 {"locateAcceptSent":"true"}
     * even for a made-up quoteReqId, so a `true` here is NOT proof the locate was granted.
     * Re-read [getLocateHistory] to find out.
     */
    suspend fun acceptLocate(
        accountId: String,
        quoteReqId: String,
    ): Boolean {
        require(quoteReqId.isNotBlank()) {
            "quoteReqId is required: accepting with a blank id would silently ack nothing"
        }
        val resp =
            client.post("$base/accounts/locates/accept") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("accountId", accountId)
                        put("quoteReqID", quoteReqId)
                    },
                )
            }
        return jsonOrThrow(resp)["locateAcceptSent"]?.jsonPrimitive?.content?.lowercase() == "true"
    }

    /**
     * Request a locate and wait for the broker's answer, or null on timeout.
     *
     * Quotes carry no usable correlation id in paper (`quoteReqID` comes back as the
     * literal `<no value>`), so the new entry is identified by diffing the history against
     * a snapshot taken beforehand. Should the API start populating quoteReqID, match on it
     * instead — it is strictly more reliable.
     *
     * [timeoutMs] stays well under the ~30s quote expiry observed in production.
     */
    suspend fun awaitLocateQuote(
        accountId: String,
        symbol: String,
        shares: Int,
        timeoutMs: Long = 10_000,
        pollMs: Long = 500,
    ): LocateQuote? {
        val before = getLocateHistory(accountId).map { it.symbol to it.createdDate }.toSet()
        if (!requestLocate(accountId, symbol, shares)) return null
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(pollMs)
                val fresh =
                    getLocateHistory(accountId).firstOrNull {
                        it.symbol.equals(symbol, ignoreCase = true) &&
                            (it.symbol to it.createdDate) !in before &&
                            it.isSettled
                    }
                if (fresh != null) return@withTimeoutOrNull fresh
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /**
     * A client order id is consumed permanently on acceptance — never reuse one. Kept
     * under 36 chars, which TradeZero requires for reliable venue cancels on live.
     */
    private fun newClientOrderId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private fun parseLocateQuote(o: JsonObject): LocateQuote {
        val rawId = o["quoteReqID"]?.jsonPrimitive?.contentOrNull
        return LocateQuote(
            accountId = o["accountId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            symbol = o["symbol"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            shares = o["locateShares"]?.jsonPrimitive?.intOrNull ?: 0,
            pricePerShare = o["locatePrice"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            status = o["locateStatus"]?.jsonPrimitive?.intOrNull ?: 0,
            // Paper renders an unset field as the literal "<no value>" (Go template default).
            quoteReqId = rawId?.takeUnless { it.isBlank() || it == "<no value>" },
            filledShares = o["filledShares"]?.jsonPrimitive?.intOrNull ?: 0,
            error = o["locateError"]?.jsonPrimitive?.intOrNull ?: 0,
            text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdDate = o["createdDate"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            updatedDate = o["updatedDate"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
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
        val clientOrderId = newClientOrderId(clientOrderIdPrefix)
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
