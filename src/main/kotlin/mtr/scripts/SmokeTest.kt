package mtr.scripts

import kotlinx.coroutines.runBlocking
import mtr.MassiveProvider
import mtr.TradeZeroConnector
import mtr.TradeZeroCredentials

/**
 * Read-only connector smoke test — NO orders placed.
 * Run: ./gradlew smoke   (defaults to AAPL)
 */
fun main(args: Array<String>) =
    runBlocking {
        val ticker = args.getOrNull(0)?.uppercase() ?: "AAPL"

        // --- Massive (market data) ---
        println("=== Massive ($ticker) ===")
        val massive = MassiveProvider.fromEnv()
        try {
            val ref = massive.getReference(ticker)
            println("  getReference OK  sharesOutstanding(float proxy)=${ref.sharesOutstanding}  marketCap=${ref.marketCapUsd}")
        } catch (e: Exception) {
            println("  getReference FAIL  ${e::class.simpleName}: ${e.message}")
        } finally {
            massive.close()
        }

        // --- TradeZero (execution + P&L) ---
        val creds = TradeZeroCredentials.fromEnv()
        println("=== TradeZero (${creds.environment}) ===")
        val tz = TradeZeroConnector(creds)
        try {
            val acc = tz.accountId()
            println("  account = $acc")
            val pnl = tz.getPnl(acc)
            println("  accountValue=${pnl["accountValue"]}  dayPnl=${pnl["dayPnl"]}  exposure=${pnl["exposure"]}")
            println("  positions = ${tz.getPositions(acc).size}")
            println("  isEasyToBorrow($ticker) = ${tz.isEasyToBorrow(acc, ticker)}")
        } finally {
            tz.close()
        }
    }
