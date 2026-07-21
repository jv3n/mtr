package mtr.scripts

import kotlinx.coroutines.runBlocking
import mtr.TradeZeroConnector
import mtr.TradeZeroCredentials

/**
 * PAPER: close (cover) the open position for a symbol via flatten(). Paper only.
 * Run: ./gradlew cover -Psym=SHPH
 */
fun main(args: Array<String>) =
    runBlocking {
        val symbol = (args.getOrNull(0) ?: "SHPH").uppercase()

        val creds = TradeZeroCredentials.fromEnv()
        if (creds.environment != "paper") {
            println("REFUSING: TRADEZERO_ENV=${creds.environment} (paper only)")
            return@runBlocking
        }

        val tz = TradeZeroConnector(creds)
        try {
            val acc = tz.accountId()
            println("account=$acc  $symbol net shares before = ${tz.netShares(acc, symbol)}")

            val result = tz.flatten(acc, symbol)
            println("flatten -> accepted=${result.accepted}  id=${result.orderId}  reason=${result.reason}")

            println("$symbol net shares after = ${tz.netShares(acc, symbol)}")
        } finally {
            tz.close()
        }
    }
