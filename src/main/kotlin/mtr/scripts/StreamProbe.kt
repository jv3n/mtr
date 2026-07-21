package mtr.scripts

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mtr.TradeZeroConnector
import mtr.TradeZeroCredentials
import mtr.TradeZeroStream

/**
 * Read-only WebSocket probe — connects, authenticates, subscribes, prints the first
 * few messages, then closes. No orders placed.
 * Run: ./gradlew stream -Pstream=portfolio   (or -Pstream=pnl)
 */
fun main(args: Array<String>) =
    runBlocking {
        val which = (args.getOrNull(0) ?: "portfolio").lowercase()

        val creds = TradeZeroCredentials.fromEnv()
        val ws = TradeZeroStream(creds)
        val tz = TradeZeroConnector(creds)
        try {
            val account = tz.accountId()
            println("connecting '$which' stream for account=$account (env=${creds.environment}) ...")
            var n = 0
            withTimeoutOrNull(12_000) {
                val flow = if (which == "pnl") ws.pnl(account) else ws.portfolio(account)
                flow.take(5).collect { msg ->
                    n++
                    println("[$n] " + msg.toString().take(400))
                }
            }
            println("received $n message(s)")
        } finally {
            ws.close()
            tz.close()
        }
    }
