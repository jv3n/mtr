package mtr

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * TradeZero WebSocket client (real-time P&L + Portfolio streams).
 *
 * Base: wss://webapi.tradezero.com/stream  →  /pnl and /portfolio
 * Handshake: connect → server sends {"@system":true,"status":"PENDING_AUTH"} →
 * send {key, secret} → server sends CONNECTED → send the stream's subscribe payload.
 *
 * NB: locate quotes do NOT travel over the WebSocket at all — probed live on 2026-07-22
 * (see #4). They land in `GET /accounts/:id/locates/history` a couple of seconds after the
 * quote request; see TradeZeroConnector.awaitLocateQuote. This client covers pnl/portfolio.
 */
class TradeZeroStream(
    private val creds: TradeZeroCredentials,
    baseUrl: String = "wss://webapi.tradezero.com",
) {
    private val base = baseUrl.trimEnd('/') + "/stream"
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val client = HttpClient(CIO) { install(WebSockets) }

    fun close() = client.close()

    /** Order + Position updates for an account. */
    fun portfolio(accountId: String): Flow<JsonObject> =
        stream(
            "/portfolio",
            buildJsonObject {
                put("accountId", accountId)
                putJsonArray("subscriptions") {
                    add("Order")
                    add("Position")
                }
            },
        )

    /** Real-time account P&L. */
    fun pnl(accountId: String): Flow<JsonObject> = stream("/pnl", buildJsonObject { put("account", accountId) })

    private fun stream(
        path: String,
        subscribe: JsonObject,
    ): Flow<JsonObject> =
        flow {
            client.webSocket("$base$path") {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val msg = json.parseToJsonElement(frame.readText()).jsonObject
                    val system = msg["@system"]?.jsonPrimitive?.booleanOrNull == true
                    val status = msg["status"]?.jsonPrimitive?.contentOrNull
                    when {
                        system && status == "PENDING_AUTH" ->
                            send(
                                Frame.Text(
                                    buildJsonObject {
                                        put("key", creds.apiKey)
                                        put("secret", creds.apiSecret)
                                    }.toString(),
                                ),
                            )
                        system && status == "CONNECTED" -> send(Frame.Text(subscribe.toString()))
                        system -> Unit // other system/heartbeat messages
                        else -> emit(msg)
                    }
                }
            }
        }
}
