package mtr.scripts

import kotlinx.coroutines.runBlocking
import mtr.TradeZeroConnector
import mtr.TradeZeroCredentials

/**
 * PAPER order test — places ONE short order and reports. Paper only.
 * Run: ./gradlew order -Psym=SHPH -Pqty=10
 */
fun main(args: Array<String>) =
    runBlocking {
        val symbol = (args.getOrNull(0) ?: "SHPH").uppercase()
        val qty = args.getOrNull(1)?.toIntOrNull() ?: 10

        val creds = TradeZeroCredentials.fromEnv()
        if (creds.environment.isLive) {
            println("REFUSING: TRADEZERO_ENV=${creds.environment} (paper only for this test)")
            return@runBlocking
        }

        val tz = TradeZeroConnector(creds)
        try {
            val acc = tz.accountId()
            println("account=$acc  symbol=$symbol  qty=$qty  env=${creds.environment}")
            println("isEasyToBorrow($symbol) = ${tz.isEasyToBorrow(acc, symbol)}")
            println("net shares before = ${tz.netShares(acc, symbol)}")

            val result = tz.submitShort(acc, symbol, qty)
            println("order accepted=${result.accepted}  id=${result.orderId}")

            println("net shares after = ${tz.netShares(acc, symbol)}")
        } finally {
            tz.close()
        }
    }
