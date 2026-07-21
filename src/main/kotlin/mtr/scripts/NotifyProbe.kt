package mtr.scripts

import kotlinx.coroutines.runBlocking
import mtr.notifierFromEnv

/**
 * Send one test alert through the configured Notifier (Telegram if TELEGRAM_* are set,
 * else log-only). Reads .env — your token never leaves your machine.
 * Run: ./gradlew notify -Pmsg="hello"
 */
fun main(args: Array<String>) =
    runBlocking {
        val message = args.getOrNull(0) ?: "mtr test alert ✅"
        val notifier = notifierFromEnv()
        println("sending via ${notifier::class.simpleName} ...")
        notifier.send(message)
        notifier.close()
    }
