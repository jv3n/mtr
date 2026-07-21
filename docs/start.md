# Projet — Bot de short sur pump & dump (small-caps) via TradeZero

> Brief de projet **d'origine** — point de départ historique. Le projet a évolué depuis (Kotlin/JVM au lieu de Python, Nautilus abandonné, direction **100 % autonome**). **Source de vérité actuelle : `CLAUDE.md`.**

---

## 1. Objectif

Construire un bot de trading **autonome** (sans intervention humaine) qui :

- surveille un **univers de ~10 small-caps** (actions entre **1 $ et 10 $**) fourni en **JSON** (à terme via un **scanner autonome**) ;
- détecte les **pump & dump** à l'ouverture ;
- **shorte** les titres en retournement, **tout seul** ;
- tourne en continu sans supervision — l'utilisateur observe simplement le rendement.

**Marché** : actions / ETF US.
**Fonction** : exécution automatisée autonome.
**Note** : brief écrit à l'origine pour Python ; le projet est désormais en **Kotlin/JVM** (voir `CLAUDE.md`).

---

## 2. Choix du moteur

Deux options envisagées :

| Option | Pour | Contre |
|---|---|---|
| **NautilusTrader** | Moteur événementiel puissant, backtest + live avec le même code, production-grade | Pas de connecteur TradeZero natif, pas de scanner, pas d'UI, courbe d'apprentissage raide |
| **Python custom léger** (recommandé pour ce cas) | Logique d'exécution simple, edge concentré sur scan + locates (hors périmètre Nautilus), plus direct à brancher sur l'API TradeZero | À construire soi-même, moins de garde-fous intégrés |

**Décision à trancher** : comme l'edge repose surtout sur la **sélection des titres** et les **locates TradeZero** (deux choses que Nautilus ne fournit pas), une version **Python custom légère** est probablement plus pragmatique. Nautilus reste valable si on veut un backtest fin et une base extensible.

> Point clé : dans les deux cas, on **n'a pas besoin de cloner le dépôt Nautilus**. On l'installe comme dépendance avec sa **version épinglée** (ex. `nautilus_trader==1.208.0` dans `requirements.txt`). Le repo ne contient que *notre* code.

---

## 3. Intégration TradeZero

TradeZero dispose désormais d'une **API officielle** (à privilégier).

- **Portail** : https://developer.tradezero.com/docs/documentation
- **REST + WebSocket** — mêmes endpoints que ZeroPro / TZ1 / ZeroMobile
- Base URL : `https://webapi.tradezero.com`
- Ordres **market / limit / stop**, **short selling + short locates**, options multi-legs
- P&L et statut des ordres en **temps réel via WebSocket**
- Authentification par **clé API** (ou OAuth)
- **Environnement paper ET live** sur la même base (clé différente) → tester sans risque
- **Exemples Python fournis**

L'API sert **à la fois** de source de données temps réel (WebSocket) et d'exécution pour les 10 titres → pas besoin d'un second fournisseur de données au départ.

> À éviter : les paquets non-officiels type `shner-elmo/tradezero-api` qui pilotent la plateforme web via Selenium (fragile, plus vraiment maintenu). L'API officielle rend ça obsolète.

---

## 4. Architecture du bot

Flux principal :

1. **Chargement** de la watchlist JSON (~10 tickers du jour)
2. **Abonnement** aux données temps réel de ces tickers (WebSocket TradeZero)
3. À l'ouverture, la **stratégie de détection** tourne sur chaque titre
   (retournement, cassure du VWAP, épuisement de volume, % de hausse… critères à définir)
4. Signal → **vérification du locate** TradeZero (dispo ? coût ?)
5. Si OK → **ordre short**
6. **Gestion de position** : stop, prise de profit, limite de perte journalière
7. **Gestion des halts** : règle simple (flatten ou gel de la position)

### Modules à écrire

- `watchlist.py` — charge et valide le JSON
- `strategy.py` — logique de détection du dump par ticker
- `tradezero_connector.py` — données + ordres + locates via l'API officielle
- `risk.py` — garde-fous (voir §6)
- `main.py` — orchestration / boucle principale
- `config/` — clés (via Secret Manager en prod, jamais en dur)

---

## 5. Points critiques spécifiques à cette stratégie

- **Locates (LE point clé)** : les small-caps qui pumpent sont souvent *hard-to-borrow*. Le bot doit vérifier/sécuriser un locate avant de shorter. TradeZero est réputé pour ses locates → atout. Sur 10 titres, possible de le faire de façon semi-manuelle (« locate dispo à X $, je shorte ? »).
- **Halts** : circuit breakers LULD fréquents sur ces titres. Prévoir une règle de gestion explicite.
- **Données / backtest** : historique intraday small-cap cher et de qualité inégale, et **historique des locates quasi inexistant** → une grande partie de la validation se fera en **paper trading temps réel**, pas sur historique.
- **Règle Pattern Day Trader (PDT)** : minimum **25 000 $ d'équité** sur compte margin pour faire ≥ 4 day trades sur 5 jours ouvrés. À vérifier avant de lancer en réel.

---

## 6. Garde-fous (non optionnels)

Un bot autonome sur compte réel est dangereux sans protections. Au minimum :

- **Limite de perte journalière** — arrêt auto si perte > seuil
- **Limites de taille de position** (par trade et global)
- **Kill switch** — arrêt d'urgence manuel
- **Gestion d'erreurs robuste** — que se passe-t-il si l'API ne répond pas au milieu d'un ordre ?
- **Surveillance + alertes** (email / Telegram) si le process s'arrête ou si la connexion tombe
- **Toujours démarrer en paper trading**, plusieurs semaines, avant le moindre centime réel

---

## 7. Déploiement

### En local (test)
- Python 3.11+
- `pip install -r requirements.txt` (avec version Nautilus épinglée si utilisé)
- Lancer en paper trading d'abord

### Sur GCP (production, 24/7)
- **Compute Engine** (VM `e2-small` / `e2-medium`, ~15–30 $/mois) — PAS Cloud Run (le bot est un process persistant en WebSocket)
- **Région proche de la côte est US** (ex. `us-east4`) pour la latence
- Bot en **Docker** (image officielle Nautilus dispo) ; `Dockerfile` → `pip install` → lance le bot
- Redémarrage auto via **systemd** ou `restart: always` Docker
- **Clés API dans Secret Manager**, jamais en dur dans le code
- Disque persistant pour logs + alerte si le process meurt
- Tester en paper sur la VM avant de brancher le live

---

## 8. Gestion des dépendances

- Le repo = **mon code uniquement** (stratégie, connecteur, config, watchlist)
- Nautilus (si utilisé) = **dépendance épinglée** dans `requirements.txt` → `nautilus_trader==X.Y.Z`
- **Ne jamais cloner/vendorer** le dépôt Nautilus (sauf besoin de modifier son cœur → recompilation Rust, lourd)
- Épingler la version = garantie que la prod = ce qui a été testé

### Arborescence suggérée

```
mon-bot/
├── main.py
├── strategy.py
├── watchlist.py
├── tradezero_connector.py
├── risk.py
├── config/
│   └── settings.example.yaml
├── data/
│   └── watchlist.json
├── requirements.txt
├── Dockerfile
└── README.md
```

---

## 9. Rappel risque

Ceci n'est **pas un conseil financier**. Shorter des pump & dump est une des stratégies **les plus risquées** :
risque théoriquement illimité (short squeeze de +300 %), halts qui piègent, borrows coûteux.
La majorité des particuliers en trading automatisé perdent de l'argent, surtout au début.
→ Phase **paper trading** longue + garde-fous stricts = indispensables.

---

## 10. Prochaines étapes

- [ ] Décider : **Nautilus** ou **Python custom léger**
- [ ] Ouvrir un accès API TradeZero + récupérer clé **paper**
- [ ] Définir précisément les **critères de détection** du dump
- [ ] Scaffolder le repo (structure §8)
- [ ] Écrire le connecteur TradeZero (données + ordres + locate)
- [ ] Implémenter les garde-fous (§6)
- [ ] Tester en **paper trading** (plusieurs semaines)
- [ ] Déployer sur GCP (§7)
- [ ] Validation continue avant tout passage en réel
