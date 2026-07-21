# mtr

Autonomous bot that shorts pump & dump moves on US small-caps (stocks $1–$10) via TradeZero.

**Operating model:** fully hands-off. The bot runs unattended, detects and trades entirely
on its own through the TradeZero connector, and logs its P&L — you just watch whether it makes money.
No human validation in the loop (it only shorts easy-to-borrow names for now).

See `docs/start.md` for the original brief and `CLAUDE.md` for current conventions.

> ⚠️ Highly risky strategy. Always run in **paper trading**. Not financial advice.

## Stack

- **Kotlin / JVM 25**, built with **Gradle** (wrapper 9.3.0, Kotlin 2.3.0).
- Ktor client (REST + WebSocket), kotlinx-serialization, coroutines, dotenv-kotlin.
- Develop/run directly in **IntelliJ on Windows**; Docker (JRE 25) for production.
- **TradeZero** = execution + short locates + P&L (no market data).
- **Massive** (formerly Polygon.io) = market data. Real-time needs the **Advanced** plan;
  Basic (free) has no WebSocket, Starter/Developer are 15-min delayed.

## Setup

```bash
cp .env.example .env          # add your PAPER TradeZero keys + Massive key
cp data/watchlist.example.json data/watchlist.json
```

`.env`:
```
TRADEZERO_API_KEY=...
TRADEZERO_API_SECRET=...
TRADEZERO_ENV=paper
MASSIVE_API_KEY=...
# On the delayed (Starter) tier, use the delayed WS host:
# MASSIVE_WS_URL=wss://delayed.massive.com/stocks
```

## Run

```bash
./gradlew smoke                     # read-only connector check (no orders)
./gradlew order -Psym=SHPH -Pqty=10 # PAPER: place one short
./gradlew cover -Psym=SHPH          # PAPER: cover/close a position
./gradlew run                       # autonomous paper-trading loop
```

> On Windows CLI outside IntelliJ, set `JAVA_HOME` to a JDK 25 first (e.g. IntelliJ's bundled JBR).

## Layout

```
src/main/kotlin/mtr/
  Main.kt                 # autonomous loop (entry / stop / take-profit) + P&L logging
  Strategy.kt             # dump detection + exit rules + StrategyParams
  Watchlist.kt            # load + validate the daily JSON
  TradeZeroConnector.kt   # execution + locates + P&L (schemas validated in paper)
  MarketData.kt           # MassiveProvider (REST reference + WebSocket aggregates)
  Risk.kt                 # guardrails (mandatory)
  Config.kt               # secrets (.env) + risk limits
  scripts/                # SmokeTest / OrderTest / Cover (run via Gradle tasks above)
```

## Deployment

Currently a **24/7 paper test**. Production target: **GCP Compute Engine** (persistent
WebSocket process), Docker (JRE 25), secrets via **Secret Manager**. See `CLAUDE.md`.
