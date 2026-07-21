# Mémo gcloud — ops du bot

Valeurs du projet (copier-coller direct) :

```bash
PROJECT=mtr-bot-1784670138
ZONE=us-east4-c
REGION=us-east4
BILLING=0159AE-56FF40-037FC8
gcloud config set project "$PROJECT"
```

## VM — démarrer / arrêter / état
```bash
gcloud compute instances start mtr --zone "$ZONE"      # allumer (pour tester hors fenêtre)
gcloud compute instances stop  mtr --zone "$ZONE"      # éteindre
gcloud compute instances describe mtr --zone "$ZONE" --format='value(status)'
gcloud compute instances list                          # IP, statut
gcloud compute ssh mtr --zone "$ZONE"                  # SSH
```

## Voir les logs
```bash
# Logs du conteneur (driver gcplogs) — sortie du bot :
gcloud logging read 'resource.type="gce_instance"' --freshness=2h --limit=50 \
  --format='value(timestamp, jsonPayload.message, textPayload)'

# Boot / startup-script (debug démarrage) :
gcloud compute instances get-serial-port-output mtr --zone "$ZONE" | tail -60

# Directement sur la VM :
gcloud compute ssh mtr --zone "$ZONE" --command='cd /opt/mtr && sudo docker compose logs --tail 100 -f'
```

## Fenêtre horaire (Cloud Scheduler)
```bash
gcloud scheduler jobs list --location "$REGION"
gcloud scheduler jobs run   mtr-start --location "$REGION"   # forcer un démarrage maintenant
gcloud scheduler jobs run   mtr-stop  --location "$REGION"   # forcer un arrêt maintenant
gcloud scheduler jobs pause  mtr-start --location "$REGION"  # suspendre la planif
gcloud scheduler jobs resume mtr-start --location "$REGION"
```

## Secrets
```bash
gcloud secrets list
gcloud secrets versions access latest --secret=mtr-TRADEZERO_ENV   # ⚠️ affiche la valeur en clair
# Re-pousser depuis .env (nouvelle version des secrets) :
bash deploy/gcp-setup.sh setup      # (PROJECT_ID + BILLING_ACCOUNT en env)
```

## Redéployer une nouvelle image
```bash
# L'image :latest est reconstruite à chaque push master. Au prochain boot la VM la re-pull.
# Pour forcer sans attendre :
gcloud compute ssh mtr --zone "$ZONE" --command='cd /opt/mtr && sudo docker compose pull && sudo docker compose up -d'
```

## Kill switch à distance
```bash
gcloud compute ssh mtr --zone "$ZONE" --command='touch /opt/mtr/KILL'   # le bot flatten + s'arrête
```

## Budget & coûts
```bash
gcloud services enable billingbudgets.googleapis.com
# Budget 15 (devise du compte) sur CE projet, alertes à 50/90/100 % :
gcloud billing budgets create --billing-account="$BILLING" \
  --display-name="mtr budget" --budget-amount=15 \
  --filter-projects="projects/$PROJECT" \
  --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0
gcloud billing budgets list --billing-account="$BILLING"
```

## Tout re-provisionner (si besoin)
```bash
PROJECT_ID=$PROJECT BILLING_ACCOUNT=$BILLING bash deploy/gcp-setup.sh setup
PROJECT_ID=$PROJECT bash deploy/gcp-setup.sh vm     # (image GHCR publique requise)
```
