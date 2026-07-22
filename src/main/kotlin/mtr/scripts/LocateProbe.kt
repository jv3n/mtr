package mtr.scripts

import kotlinx.coroutines.runBlocking
import mtr.LocateQuote
import mtr.TradeZeroApiException
import mtr.TradeZeroConnector
import mtr.TradeZeroCredentials

/**
 * Locate probe. READ-ONLY by default: dumps the locate history and inventory.
 *
 * With `-Pquote=SYMBOL -Pqty=N` it also requests a quote and waits for the broker's answer
 * (paper only). That path is currently blocked API-side — see issue #4: paper never
 * populates `quoteReqID`, and every quote after the first fails with
 * `R114: Invalid duplicate UserOrderId`.
 *
 * Run: ./gradlew locate                          (read-only)
 *      ./gradlew locate -Pquote=SHPH -Pqty=100   (requests a quote, paper only)
 */
fun main(args: Array<String>) =
    runBlocking {
        val symbol = args.getOrNull(0)?.uppercase()?.takeIf { it.isNotBlank() }
        val qty = args.getOrNull(1)?.toIntOrNull() ?: 100

        val creds = TradeZeroCredentials.fromEnv()
        val tz = TradeZeroConnector(creds)
        try {
            val account = tz.accountId()
            println("account=$account  env=${creds.environment}")

            println("\n--- locate inventory ---")
            val inventory = tz.getLocateInventory(account)
            if (inventory.isEmpty()) println("  (empty)") else inventory.forEach { println("  $it") }

            println("\n--- locate history ---")
            val history = tz.getLocateHistory(account)
            if (history.isEmpty()) println("  (empty)") else history.forEach { println("  " + describe(it)) }

            if (symbol == null) {
                println("\n(read-only; pass -Pquote=SYMBOL to request a quote)")
                return@runBlocking
            }
            if (creds.environment.isLive) {
                println("\nREFUSING: TRADEZERO_ENV=${creds.environment} (paper only for this probe)")
                return@runBlocking
            }

            println("\n--- requesting a locate quote for $symbol x$qty ---")
            val quote =
                try {
                    tz.awaitLocateQuote(account, symbol, qty)
                } catch (e: TradeZeroApiException) {
                    println("  broker refused: ${e.apiMessage}  (HTTP ${e.status})")
                    if (e.apiMessage.contains("R114")) {
                        println("  -> expected on paper: locates are live-only (issue #4)")
                    }
                    return@runBlocking
                }
            when {
                quote == null -> println("  no quote came back before the timeout")
                quote.isError -> println("  broker error -> " + describe(quote))
                quote.isExpired -> println("  quote expired before we read it -> " + describe(quote))
                else -> {
                    println("  " + describe(quote))
                    println(
                        if (quote.isAcceptable) {
                            "  acceptable: total cost $%.2f".format(quote.totalCostUsd)
                        } else {
                            "  NOT acceptable: quoteReqID is missing, so accept() cannot target it (issue #4)"
                        },
                    )
                }
            }
        } finally {
            tz.close()
        }
    }

private fun describe(q: LocateQuote): String =
    "%s x%d  $%.4f/sh  total $%.2f  status=%d%s  reqId=%s%s".format(
        q.symbol,
        q.shares,
        q.pricePerShare,
        q.totalCostUsd,
        q.status,
        if (q.isAvailable) {
            " (available)"
        } else if (q.isExpired) {
            " (expired)"
        } else if (q.isError) {
            " (error)"
        } else {
            ""
        },
        q.quoteReqId ?: "<unset>",
        if (q.text.isNotBlank()) "  \"${q.text}\"" else "",
    )
