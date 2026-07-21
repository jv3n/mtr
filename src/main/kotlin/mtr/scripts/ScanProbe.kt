package mtr.scripts

import kotlinx.coroutines.runBlocking
import mtr.MassiveProvider
import mtr.Scanner

/**
 * Run the autonomous scanner once and print the selected universe.
 * Run: ./gradlew scan
 * NB: the gainers snapshot needs a paid Massive tier; on the free tier it 403s
 * and the scanner returns nothing (Main then falls back to the watchlist file).
 */
fun main() =
    runBlocking {
        val market = MassiveProvider.fromEnv()
        try {
            println("scanning top gainers ...")
            val candidates = Scanner(market).scan()
            if (candidates.isEmpty()) {
                println("no candidates (paid tier required for the gainers snapshot?)")
            }
            candidates.forEach { println("  ${it.ticker}  ${it.note}") }
        } catch (e: Exception) {
            println("scan failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            market.close()
        }
    }
