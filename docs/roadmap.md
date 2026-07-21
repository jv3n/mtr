# Roadmap — grosses briques (prérequis avant la data temps réel)

Ordre de construction du bot autonome. La **data temps réel payante** (Massive Advanced,
199 $/mo) est câblée **en dernier** (brique #9), une fois tout le reste prêt — un mois de
mesure suffit pour voir si ça gagne.

Voir `CLAUDE.md` pour l'état du code. Chaque brique ci-dessous = une issue GitHub.

---

## 1. Observabilité & reporting P&L
**Pourquoi** : « regarder si ça gagne » exige de mesurer proprement.
- Log structuré de chaque trade (entrée/sortie, prix, taille, raison, P&L réalisé).
- Snapshot P&L périodique (déjà ébauché dans `Main.kt`) → fichier/log persistant.
- Résumé de rendement (P&L jour, cumul, nb trades, win rate, drawdown).
- **Done** : après un run, on peut lire un récap clair du rendement.

## 2. Garde-fous & robustesse
**Pourquoi** : un bot autonome sans filet est dangereux (voir §6 du brief).
- Limite de perte journalière effective → kill switch auto (RiskManager déjà en place, à câbler de bout en bout).
- Gestion d'erreurs API robuste : timeout / échec **en plein ordre**, reconnexion, idempotence des ordres (`clientOrderId`).
- Alertes (Telegram / email) : process mort, perte de connexion, kill switch déclenché.
- **Done** : coupures réseau et erreurs API ne cassent pas le bot ni ne laissent de position fantôme.

## 3. Stratégie de détection réelle
**Pourquoi** : l'edge est ici, pas dans la data. Le `evaluate` actuel est un placeholder.
- Vrais critères de retournement pump & dump (VWAP reclaim/reject, épuisement de volume, % de hausse, extension).
- Paramètres configurables + réglables.
- **Done** : `evaluate` produit des signaux justifiables, testables sur données.

## 4. Locates via WebSocket (shorter les hard-to-borrow)
**Pourquoi** : les meilleurs pump & dumps sont souvent HTB ; aujourd'hui on ne shorte que les ETB.
- Implémenter le flux **asynchrone** : subscribe au stream locate → `requestLocate` → recevoir coût/dispo/quoteReqID via WebSocket → `acceptLocate` → short.
- Décision auto « locate dispo à X $, je shorte ? » selon un budget de coût.
- **Done** : le bot peut shorter un titre HTB de façon autonome.

## 5. Scanner autonome (sélection de l'univers)
**Pourquoi** : c'est ça le vrai « je fais rien ». Une watchlist figée = mauvais tickers.
- Le bot choisit ses ~10 tickers du jour lui-même (gappers small-cap 1–10 $ via Massive : % hausse, volume, float).
- Produit dynamiquement l'univers consommé par `Main` (remplace la watchlist manuelle).
- **Done** : aucun input humain quotidien ; le bot est sur les bons titres tout seul.

## 6. Gestion de position & halts (LULD)
**Pourquoi** : réalisme d'exécution sur small-caps.
- Sorties robustes (stop / take-profit / sortie temps), sizing propre.
- Gestion des **halts LULD** : gel ou flatten explicite.
- Awareness PDT (≥ 25 000 $ pour ≥ 4 day trades / 5 j) avant le réel.
- **Done** : positions gérées proprement même en halt / gap violent.

## 7. Validation d'expectancy (backtest / paper)
**Pourquoi** : savoir si la stratégie a un edge **avant** de payer la data.
- Rejouer la stratégie sur historique dispo et/ou sur des sessions paper enregistrées.
- Métriques d'expectancy (espérance par trade, distribution).
- **Done** : go/no-go chiffré avant de brancher le temps réel payant.

## 8. Déploiement GCP 24/7 paper
**Pourquoi** : laisser tourner sans supervision.
- Docker JVM (déjà en place) → **Compute Engine** (`e2-small`, `us-east4`).
- Secrets via **Secret Manager**, redémarrage auto, monitoring + alerte si le process meurt.
- **Done** : le bot tourne seul sur le cloud, se relance, alerte en cas de pépin.

## 9. Câbler la data temps réel — Massive Advanced (DERNIÈRE brique)
**Pourquoi** : condition nécessaire à un rendement représentatif — mais seulement une fois 1–8 prêts.
- Abonnement **Advanced 199 $/mo** (un mois de mesure).
- Retirer l'override `MASSIVE_WS_URL` (host temps réel par défaut).
- Lancer la **fenêtre de mesure** du rendement en paper.
- **Done** : le bot trade sur du temps réel et on mesure enfin s'il gagne.
