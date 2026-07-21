#!/usr/bin/env bash
# Creates the roadmap issues (in order) on the GitHub repo of the current remote.
#
# Prerequisites (one-time):
#   winget install --id GitHub.cli      # or: scoop install gh
#   gh auth login                       # interactive (browser)
#
# Then, from the repo root:
#   bash scripts/create-issues.sh
#
# Idempotency: re-running creates duplicates. Run once.

set -u

if ! command -v gh >/dev/null 2>&1; then
  echo "gh (GitHub CLI) is not installed. See the header of this script." >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "gh is not authenticated. Run: gh auth login" >&2
  exit 1
fi

# Defensive label (ignored if it already exists).
gh label create roadmap -c FBCA04 -d "Roadmap brick / prerequisite" >/dev/null 2>&1 || true

create() {
  local title="$1"
  local body="$2"
  echo "Creating: $title"
  gh issue create --title "$title" --body "$body" --label roadmap
}

create "1 · Observabilité & reporting P&L" \
"Mesurer proprement le rendement (\"regarder si ça gagne\").

**Tâches**
- Log structuré de chaque trade (entrée/sortie, prix, taille, raison, P&L réalisé).
- Snapshot P&L périodique persistant (ébauché dans Main.kt).
- Résumé de rendement : P&L jour, cumul, nb trades, win rate, drawdown.

**Done** : après un run, un récap clair du rendement est lisible."

create "2 · Garde-fous & robustesse" \
"Un bot autonome sans filet est dangereux (brief §6).

**Tâches**
- Limite de perte journalière → kill switch auto (RiskManager câblé de bout en bout).
- Gestion d'erreurs API : timeout / échec en plein ordre, reconnexion, idempotence (clientOrderId).
- Alertes (Telegram/email) : process mort, perte de connexion, kill switch.

**Done** : coupures réseau/erreurs API ne cassent pas le bot ni ne laissent de position fantôme."

create "3 · Stratégie de détection réelle" \
"L'edge est ici, pas dans la data. evaluate() est un placeholder.

**Tâches**
- Vrais critères de retournement pump & dump (VWAP reclaim/reject, épuisement volume, % hausse, extension).
- Paramètres configurables + réglables.

**Done** : evaluate() produit des signaux justifiables et testables."

create "4 · Locates via WebSocket (shorter les hard-to-borrow)" \
"Les meilleurs pump & dumps sont souvent HTB ; on ne shorte que les ETB aujourd'hui.

**Tâches**
- Flux asynchrone : subscribe stream locate → requestLocate → recevoir coût/dispo/quoteReqID via WS → acceptLocate → short.
- Décision auto selon un budget de coût de locate.

**Done** : le bot peut shorter un titre HTB de façon autonome."

create "5 · Scanner autonome (sélection de l'univers)" \
"Le vrai « je fais rien ». Une watchlist figée = mauvais tickers.

**Tâches**
- Le bot choisit ses ~10 tickers du jour (gappers small-cap 1–10 \$ via Massive : % hausse, volume, float).
- Produit dynamiquement l'univers consommé par Main (remplace la watchlist manuelle).

**Done** : aucun input humain quotidien ; le bot est sur les bons titres tout seul."

create "6 · Gestion de position & halts (LULD)" \
"Réalisme d'exécution sur small-caps.

**Tâches**
- Sorties robustes (stop / take-profit / sortie temps), sizing propre.
- Gestion des halts LULD : gel ou flatten explicite.
- Awareness PDT (≥ 25 000 \$ pour ≥ 4 day trades / 5 j) avant le réel.

**Done** : positions gérées proprement même en halt / gap violent."

create "7 · Validation d'expectancy (backtest / paper)" \
"Savoir si la stratégie a un edge AVANT de payer la data.

**Tâches**
- Rejouer la stratégie sur historique dispo et/ou sessions paper enregistrées.
- Métriques d'expectancy (espérance par trade, distribution).

**Done** : go/no-go chiffré avant de brancher le temps réel payant."

create "8 · Déploiement GCP 24/7 paper" \
"Laisser tourner sans supervision.

**Tâches**
- Docker JVM (en place) → Compute Engine (e2-small, us-east4).
- Secrets via Secret Manager, redémarrage auto, monitoring + alerte si le process meurt.

**Done** : le bot tourne seul sur le cloud, se relance, alerte en cas de pépin."

create "9 · Câbler la data temps réel — Massive Advanced (DERNIÈRE brique)" \
"Condition nécessaire à un rendement représentatif — seulement une fois 1–8 prêts.

**Tâches**
- Abonnement Advanced 199 \$/mo (un mois de mesure).
- Retirer l'override MASSIVE_WS_URL (host temps réel par défaut).
- Lancer la fenêtre de mesure du rendement en paper.

**Done** : le bot trade sur du temps réel et on mesure enfin s'il gagne."

echo "Done. Issues created on: $(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)"
