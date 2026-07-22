# CLAUDE.md

Guide pour Claude Code sur ce dépôt. Voir `docs/start.md` pour le brief complet et les décisions détaillées.

## Ce qu'est le projet

Bot de trading **100 % autonome** qui shorte des **pump & dump** sur small-caps US (actions 1–10 $) via **TradeZero**.

**Mode de fonctionnement (direction actuelle)** : totalement **sans intervention humaine**. Le bot tourne seul, détecte et trade tout seul via le connecteur TradeZero, et log son P&L. L'objectif de la phase actuelle est simple : **le laisser tourner en paper et regarder s'il gagne**. Pas de validation manuelle dans la boucle (il ne shorte que les titres **easy-to-borrow** pour l'instant, faute de stream de locate).

Flux : watchlist (univers de tickers) → quotes temps réel (WebSocket Massive) → détection du retournement → contrôle ETB + garde-fous → ordre **short** → gestion de position (stop / TP / limite de perte journalière) → gestion des **halts**.

- **Marché** : actions / ETF US.
- **Portée** : exécution **autonome** (pas de human-in-the-loop). Le kill switch reste une commande d'urgence manuelle (arrêt), pas une validation de trade.
- **Local** d'abord (paper trading), puis **GCP** en continu.

## Langage & environnement

- **Kotlin / JVM 25** (LTS). ⚠️ **Pivot depuis Python** (décidé en cours de projet — l'utilisateur est plus à l'aise en JVM ; le repo avait démarré en Java). **Nautilus et Python sont abandonnés** — plus aucune contrainte Python.
- **Build** : Gradle (wrapper **9.3.0**) + plugin **Kotlin 2.3.0**. JDK de compile = **JDK 25** via `jvmToolchain(25)`. En local hors IntelliJ, utiliser le **JBR d'IntelliJ** comme JDK 25 : `JAVA_HOME="C:/Program Files/JetBrains/IntelliJ IDEA 2026.1.3/jbr"` (IntelliJ le gère automatiquement).
- **Dev direct dans IntelliJ sous Windows** (contrairement à Python) — plus besoin de WSL pour le dev. WSL/Docker restent pour la prod Linux uniquement.
- **Stack** : Ktor client (REST + WebSocket, coroutines) · kotlinx-serialization (JSON) · dotenv-kotlin (`.env`) · slf4j-simple (logs).
- **Commandes** (depuis la racine ; en CLI hors IntelliJ, exporter `JAVA_HOME` vers un JDK 25, ex. le JBR d'IntelliJ) :
  - Smoke-test connecteur (read-only, aucun ordre) : `./gradlew smoke` — ✅ TradeZero validé en paper.
  - Lancer l'app : `./gradlew run`.
- **État du port** : `Config` + `TradeZeroConnector` (validé) portés en Kotlin sous `src/main/kotlin/mtr/`. **Restent à porter** : market data (Massive), strategy, risk, watchlist, main, + scripts order/cover. Le code **Python reste temporairement** dans `mtr/` + `scripts/` comme **référence de port** (schémas API déjà validés) — à supprimer une fois le port terminé.

## Langue du code

- **Tout le code est en anglais** : identifiants, commentaires, docstrings, messages de log, noms de fichiers. Sans exception.
- La **documentation projet** (`CLAUDE.md`, `docs/`) et **nos échanges** restent en **français**.

## Décisions clés (déjà prises)

- **API TradeZero officielle** — REST (préfixe `/v1/api`) + WebSocket, base URL `https://webapi.tradezero.com`. Doc : https://developer.tradezero.com/docs/documentation
  - **Périmètre réel : exécution + locates + P&L UNIQUEMENT.** ⚠️ **Pas de données de marché** (quotes/charts/historique indisponibles sur l'API publique). Le WebSocket ne diffuse que P&L + statut des ordres.
  - Endpoints : comptes/positions/pnl, ordres (`POST /accounts/:id/order`, cancel), `is-easy-to-borrow`, locates (`POST /accounts/locates/quote|accept` ; `GET /accounts/:id/locates/inventory|history` — ⚠️ inventory et history sont **scopés par compte**, les formes sans id renvoient 404).
  - **Auth** : simple, par headers sur chaque requête REST — `TZ-API-KEY-ID` (clé publique/UUID) + `TZ-API-SECRET-KEY` (secret). Pas de HMAC ni OAuth. La paire de clés route paper vs live.
  - Environnements **paper** et **live** sur la même API (clé différente). **Toujours démarrer en paper.** Modélisé par l'enum **`TradingEnv`** (`Config.kt`) et non par des comparaisons de chaînes : elle porte les différences de comportement (`defaultRoute`, `requiresLocateForHardToBorrow`) — c'est là qu'on ajoutera les spécificités d'un futur broker. `TradingEnv.parse()` retombe sur **PAPER** pour toute valeur inconnue : une faute de frappe dans `TRADEZERO_ENV` ne doit jamais engager d'argent réel.
  - ❌ Ne pas utiliser les paquets non-officiels type `shner-elmo/tradezero-api` (pilotage web via Selenium, fragile).
  - **Schéma confirmé en paper** (via `scripts/smoke_test.py` + `scripts/order_test.py`, tout validé end-to-end) :
    - `GET /accounts` → `{"accounts":[{...}]}` ; l'id de compte est le champ **`account`** (ex. `TZP8B199`), pas `id`/`accountId`.
    - `GET /accounts/:id/positions` → `{"positions":[...]}` (enveloppé) ; position short = `side:"Short"`, `shares` négatif.
    - `GET /accounts/:id/pnl` → `accountValue`, `dayPnl`, `dayRealized`, `dayUnrealized`, `availableCash`, `exposure`, `usedLeverage`, `pnl:[]`.
    - `is-easy-to-borrow` → `{"isEasyToBorrow": bool}`.
    - **Locate = asynchrone** : `POST /accounts/locates/quote` avec `{"account","symbol","quantity"}` → renvoie seulement `{"locateQuoteSent":"true"}`. ⚠️ **Le quote n'arrive PAS par WebSocket** (hypothèse initiale invalidée, sondé en séance le 2026-07-22 — cf. issue #4) : il apparaît dans `GET /accounts/:id/locates/history` ~2 s plus tard, avec `locatePrice` (coût par action), `locateShares`, `locateStatus` (65 dispo · 67 expiré · 56 erreur). **Le quote expire en ~30 s.** `accept` exige `accountId` (⚠️ et non `account` comme `quote`) + `quoteReqID`, et ne renvoie qu'un ack (200 même avec un id bidon).
    - ⚠️ **Les locates sont LIVE-ONLY** (documenté : « locates are live-only (paper-account locate calls return `Rejected` rows) »). En paper : `quoteReqID` remonte `"<no value>"`, toute requête après la première échoue en `R114`, et `isEasyToBorrow` renvoie `true` sur tout. Ce n'est **pas** un bug à remonter au support — le flow HTB (#4) ne pourra être validé qu'en compte réel.
    - **`clientOrderId` consommé définitivement** : une fois accepté, un `clientOrderId` ne peut plus jamais être réutilisé, même après annulation ou rejet (`R114: Invalid duplicate UserOrderId`). Toujours en générer un neuf (`newClientOrderId`), y compris pour les locates et les remplacements. ≤ 36 caractères en live.
    - **Réponses d'erreur gzippées** (pas les 200) → plugin Ktor `ContentEncoding` obligatoire, sinon toute 4xx explose en erreur de désérialisation au lieu de remonter le message. `TradeZeroApiException` porte le message réel de l'API.
    - **Ordre** `POST /accounts/:id/order` : champs camelCase, enums capitalisés. **Un short = `side:"Sell"` + `openClose:"Open"`** (sell-to-open). Payload : `account, securityType:"Stock", symbol, side, openClose, orderQuantity, orderType:"Market"|"Limit", timeInForce:"Day", route, clientOrderId`. `route` = **`"PAPER"`** en paper (voir `GET /accounts/:id/routes`), `"SMART"` en live. Fermeture = ordre opposé avec `openClose:"Close"` (implémenté dans `flatten`).
- **Données de marché : fournisseur SÉPARÉ obligatoire** (TradeZero n'en fournit pas). Nécessaire pour la détection (quotes → VWAP/volume/%) ET l'UI watchlist (open/float/volume). **Choix : Massive** (ex-Polygon.io, rebrandé le 2025-10-30 ; hostnames `api.massive.com`/`socket.massive.com`, anciens `*.polygon.io` encore compatibles). `MassiveProvider` dans `MarketData.kt` — REST (Bearer) pour reference, WebSocket agrégats-seconde `A.*` pour le temps réel (portent VWAP `a` + volume jour `av`). Clé via `MASSIVE_API_KEY` (ou `POLYGON_API_KEY` legacy). NB : Massive expose les *shares outstanding*, pas le vrai *float* → utilisé comme proxy. Interface `MarketDataProvider` pour rester interchangeable. **Plans** : Basic gratuit = pas de WebSocket (inutilisable) ; Starter 29 $ = WebSocket **différé 15 min** (ok pour valider la plomberie) ; **Advanced 199 $ = seul vrai temps réel** (requis pour un rendement crédible).
  - ⚠️ **Tier gratuit = J-1 / différé 15 min, pas de temps réel** (5 req/min). Inutilisable pour la détection intraday → le live exige un abonnement **Stocks temps réel payant**. En dev, pointer `MASSIVE_WS_URL` sur `wss://delayed.massive.com/stocks`.
- **Moteur** : **abandonné (plus de Nautilus)**. Bot **custom en Kotlin** — boucle de décision maison (`strategy → risk → order`). Le backtest (seul vrai atout de Nautilus) a peu de valeur ici (pas d'historique de locates). L'autonomie vient de la boucle, pas d'un framework (voir section « Note moteur » plus bas).
- **Watchlist / univers** : le bot lit son univers de tickers dans `data/watchlist.json` (fallback : `data/watchlist.example.json`). Vu la nouvelle direction **« zéro intervention »**, le vrai objectif est un **scanner autonome** qui choisit les tickers lui-même (à construire) → hands-off total. L'ancienne idée d'**UI web** de sélection manuelle devient **optionnelle / dépriorisée**. En attendant le scanner, l'univers est un JSON fourni une fois.
- **Dépendances** : gérées par **Gradle** (`build.gradle.kts`). Ktor client · kotlinx-serialization · kotlinx-coroutines · dotenv-kotlin · slf4j-simple.
- **Secrets** : clés API jamais en dur. Local = `.env` (git-ignoré) via dotenv-kotlin ; prod = **GCP Secret Manager** → variables d'env.

## Structure (Kotlin)

```
src/main/kotlin/mtr/
  Main.kt                 # boucle autonome (quote → gestion sortie / entrée short) + log P&L
  Strategy.kt             # détection du dump + règles de sortie (stop/TP) + StrategyParams
  Watchlist.kt            # charge et valide le JSON du jour
  TradeZeroConnector.kt   # exécution + locates + P&L (Ktor REST) — schémas validés en paper
  MarketData.kt           # MassiveProvider : REST reference + WebSocket agrégats (interface MarketDataProvider)
  Risk.kt                 # garde-fous (RiskManager, obligatoire)
  Config.kt               # secrets (.env) + RiskLimits + enum TradingEnv (PAPER/LIVE)
  scripts/                # SmokeTest, OrderTest, Cover (mains lancés via tâches Gradle)
build.gradle.kts · settings.gradle.kts · gradlew · Dockerfile · .env.example · data/watchlist.example.json
```

Fichiers locaux **git-ignorés** (à créer depuis les `.example`) : `.env`, `data/watchlist.json`.
Commandes : `./gradlew smoke` (read-only), `./gradlew order -Psym=SHPH -Pqty=10`, `./gradlew cover -Psym=SHPH`, `./gradlew run` (boucle autonome).

## Garde-fous — NON optionnels

Toute logique de trading doit respecter ces protections (un bot autonome sur compte réel est dangereux sans elles) :

- **Limite de perte journalière** — arrêt auto au-delà d'un seuil.
- **Limites de taille de position** — par trade et global.
- **Kill switch** — arrêt d'urgence manuel.
- **Gestion d'erreurs robuste** — notamment API qui ne répond pas au milieu d'un ordre.
- **Surveillance + alertes** (email / Telegram) si le process s'arrête ou perd la connexion.
- **Locates** : vérifier/sécuriser un locate **avant** de shorter (small-caps souvent hard-to-borrow).
- **Halts** : règle explicite (flatten ou gel) sur circuit breakers LULD.

## Contexte réglementaire / risque

- **PDT (Pattern Day Trader)** : ≥ 25 000 $ d'équité requis pour ≥ 4 day trades / 5 jours ouvrés sur compte margin — à vérifier avant le réel.
- Stratégie parmi les **plus risquées** (risque de short squeeze théoriquement illimité, halts, borrows coûteux). Phase paper trading longue obligatoire avant tout capital réel. Pas un conseil financier.

## Déploiement — fenêtre horaire programmée (guide complet : `docs/deploy.md`)

- **Décision : fenêtre programmée (éco), pas 24/7.** VM démarrée à **7h** et éteinte après la **clôture (16h)**, **heure de New York**, via **Cloud Scheduler** (start/stop de la VM). Jours ouvrés uniquement.
- **Auto-clôture par le bot** : à `SESSION_END` (env, ex. `16:00` / `SESSION_TZ=America/New_York`) le bot **flatten tout + s'arrête** (réutilise le kill switch) → aucune position hors fenêtre. La VM est éteinte ~10 min après, juste pour le coût.
- **Image** : `.github/workflows/docker.yml` build & push `ghcr.io/jv3n/mtr:latest` à chaque push master. **Rendre le package GHCR public** pour que la VM le pull sans auth.
- **GCP Compute Engine** (`e2-small`, `us-east4`, image **`ubuntu-2204-lts`** — pas COS, dont le FS read-only casse le startup-script). `deploy/startup-script.sh` : Docker + secrets Secret Manager → `.env` + `docker compose up -d` (compose VM avec `logging: gcplogs` → logs conteneur dans Cloud Logging). Provisioning en une passe : **`deploy/gcp-setup.sh {setup|vm|all}`**.
- **Déployé & validé en prod** (2026-07-21) : projet **`mtr-bot-1784670138`**, VM `mtr`, 2 jobs scheduler (7h/16h10 ET), alerte Telegram « started » reçue. VM à l'arrêt hors fenêtre.
- **Secrets** via Secret Manager (`mtr-<VAR>`), SA VM avec `secretmanager.secretAccessor`.
- **Monitoring** : alertes Telegram du bot (start/stop/kill/halt/stream) ; un « started » hors horaire = crash/restart.
- **Dockerfile validé** (build JDK 25 → runtime JRE 25, `installDist`).

## Note moteur — autonomie ≠ Nautilus

L'autonomie du bot vient de la **boucle de décision** (`strategy → risk → order`) dans `Main.kt`, pas d'un framework. C'est pourquoi Nautilus (et Python) ont été abandonnés au profit d'un bot **custom Kotlin** plus simple et mieux maîtrisé par l'utilisateur.

## Qualité & CI

- **Spotless (ktlint)** : `./gradlew spotlessApply` pour formater, `spotlessCheck` (inclus dans `check`/`build`) pour vérifier. Style ktlint officiel via `.editorconfig` (`max-line-length` désactivé).
- **Lefthook** : hook pre-commit (`lefthook.yml`) qui lance `spotlessApply` sur les `.kt/.kts` stagés. Nécessite `lefthook install` une fois (binaire à installer : winget/scoop/npm).
- **CI** (`.github/workflows/ci.yml`) : sur push/PR → JDK 25 (Temurin) + `chmod +x gradlew` + `./gradlew spotlessCheck build`.
- **Pièges déjà réglés** (ne pas re-casser) :
  - **Fins de ligne** : tout en **LF**, forcé par `.gitattributes` (`* text=auto eol=lf`) + `.editorconfig`. IntelliJ sous Windows tend à remettre du CRLF → ktlint casse. Ne jamais retirer le `.gitattributes`.
  - **`gradlew` doit rester exécutable** (`100755` dans git) sinon la CI Linux fait `Permission denied`. Fix : `git update-index --chmod=+x gradlew`.
  - Le warning « Node 20 deprecated » en CI = interne à GitHub Actions (les actions sont en Node), **sans rapport** avec le projet.

## Conventions pour Claude

- Répondre et commenter en **français** (langue du projet).
- Avant de coder une brique de trading, s'assurer que les garde-fous concernés existent ou sont ajoutés en même temps.
- Ne jamais committer de clé API ni de secret. Utiliser `.example` pour les fichiers de config.
- Par défaut, tout nouveau code de trading cible le **paper trading**.
- **Commits : Conventional Commits** (`feat:`, `fix:`, `docs:`, `style:`, `refactor:`, `perf:`, `test:`, `build:`, `ci:`, `chore:`, `revert:`), sujet impératif en anglais ≤ 72 car. Commande `/commit` pour en générer un ; hook lefthook `commit-msg` qui valide le format.