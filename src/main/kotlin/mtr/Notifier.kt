package mtr

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Alerting for critical events (kill switch, disconnects, shutdown) — roadmap #2.
 * Always logs locally; also pushes to Telegram when configured.
 */
interface Notifier {
    suspend fun send(message: String)

    fun close() {}
}

/** Fallback when no channel is configured: log only. */
class LogOnlyNotifier : Notifier {
    override suspend fun send(message: String) = println("${Instant.now()} ALERT: $message")
}

/** Telegram Bot API notifier. Set TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID to enable. */
class TelegramNotifier(
    private val token: String,
    private val chatId: String,
) : Notifier {
    private val client = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    override suspend fun send(message: String) {
        println("${Instant.now()} ALERT: $message")
        runCatching {
            client.post("https://api.telegram.org/bot$token/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("chat_id", chatId)
                        put("text", message)
                    },
                )
            }
        }.onFailure { println("${Instant.now()} telegram send failed: ${it.message}") }
    }

    override fun close() = client.close()
}

fun notifierFromEnv(): Notifier {
    val token = envOrNull("TELEGRAM_BOT_TOKEN")
    val chatId = envOrNull("TELEGRAM_CHAT_ID")
    return if (!token.isNullOrBlank() && !chatId.isNullOrBlank()) {
        TelegramNotifier(token, chatId)
    } else {
        LogOnlyNotifier()
    }
}
