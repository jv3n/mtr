# Liens utiles

> Projet GCP : **`mtr-bot-1784670138`** (dédié au bot).

## Repo & CI/CD
- Repo : https://github.com/jv3n/mtr
- Issues (roadmap) : https://github.com/jv3n/mtr/issues
- Actions (CI / Docker) : https://github.com/jv3n/mtr/actions
- Image Docker (GHCR) : https://github.com/jv3n/mtr/pkgs/container/mtr
  - ⚠️ à rendre **public** : Package → *Package settings* → *Change visibility*

## Données — Massive (ex-Polygon.io)
- Dashboard : https://massive.com/dashboard
- Abonnements (Stocks) : https://massive.com/dashboard/subscriptions?assetClass=stocks&license=personal
- Tarifs : https://massive.com/pricing  (temps réel = plan **Advanced**)
- Docs API : https://massive.com/docs

## Courtier — TradeZero
- Portail developer : https://developer.tradezero.com/
- Documentation : https://developer.tradezero.com/docs/documentation
- Référence API : https://developer.tradezero.com/docs/TradeZeroAPI/tradezero-developer-api

## GCP — voir l'app tourner
- Console : https://console.cloud.google.com/home/dashboard?project=mtr-bot-1784670138
- VM (Compute Engine) : https://console.cloud.google.com/compute/instances?project=mtr-bot-1784670138
- **Logs du bot** (stdout du conteneur) : https://console.cloud.google.com/logs/query?project=mtr-bot-1784670138
- Cloud Scheduler (fenêtre 7h–16h) : https://console.cloud.google.com/cloudscheduler?project=mtr-bot-1784670138
- Secret Manager (`mtr-*`) : https://console.cloud.google.com/security/secret-manager?project=mtr-bot-1784670138

> Le bot n'a pas d'UI web : on le « voit » via les **logs** (Cloud Logging / `docker logs mtr` sur la VM)
> et les **alertes Telegram**. Guide de déploiement : `docs/deploy.md`.

## Coûts / pricing (à surveiller)
- **Massive** (le gros poste) : abonnements → https://massive.com/dashboard/subscriptions?assetClass=stocks&license=personal · tarifs → https://massive.com/pricing
  - Basic **gratuit** (pas de WS) · Starter **~29 $/mo** (différé, pour tester la plomberie) · Advanced **~199 $/mo** (temps réel, requis pour le vrai test #9). *Vérifie les montants actuels sur la page.*
- **GCP** : facturation → https://console.cloud.google.com/billing?project=mtr-bot-1784670138 · **budgets/alertes** → https://console.cloud.google.com/billing/budgets?project=mtr-bot-1784670138
  - VM programmée `e2-small` ~7h–16h ET, jours ouvrés ≈ **~3–5 $/mo** (+ Secret Manager/Scheduler quasi gratuits). ⚠️ **Crée un budget + alerte** pour éviter les surprises.
- **TradeZero** : API/paper gratuits (le live a des commissions, plus tard).
- **GitHub** : CI + GHCR gratuits (repo perso / quotas).

## Alertes — Telegram
- @BotFather (créer/gérer le bot) : https://t.me/BotFather
- @getidsbot (récupérer un chat_id de channel) : https://t.me/getidsbot

## Qualité (différé — issue #10)
- Qodana Cloud : https://qodana.cloud
