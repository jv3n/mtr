# Déploiement — GCP, fenêtre horaire programmée (paper)

Objectif : faire tourner le bot **seulement pendant la séance** (moins cher que 24/7).
Une VM démarrée à 7h et éteinte après la clôture (16h) **(heure de New York)** via Cloud Scheduler.

Le bot **flatten tout seul + s'arrête à `SESSION_END`** (kill switch interne), donc aucune
position n'est laissée hors fenêtre — la VM est éteinte quelques minutes après, juste pour le coût.

> ⚠️ Horaires en **America/New_York** (séance US). En gratuit/différé le bot tourne mais sans
> rendement représentatif — brancher l'abonnement temps réel Massive (#9) pour le vrai test.

## 1. Image Docker

Le workflow `.github/workflows/docker.yml` build et pousse l'image sur **GHCR** à chaque push sur `master` :
`ghcr.io/jv3n/mtr:latest`. Rends le package **public** (GitHub → Packages → mtr → Package settings →
Change visibility → Public) pour que la VM puisse `docker pull` sans auth.

## 2. Secrets (Secret Manager)

```bash
PROJECT=ton-projet
for v in TRADEZERO_API_KEY TRADEZERO_API_SECRET TRADEZERO_ENV \
         MASSIVE_API_KEY TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID; do
  printf '%s' "VALEUR_$v" | gcloud secrets create "mtr-$v" --data-file=- --project="$PROJECT"
done
# TRADEZERO_ENV = paper
```

## 3. VM (Compute Engine)

```bash
gcloud compute instances create mtr \
  --project="$PROJECT" --zone=us-east4-c --machine-type=e2-small \
  --image-family=cos-stable --image-project=cos-cloud \
  --metadata-from-file=startup-script=deploy/startup-script.sh \
  --scopes=cloud-platform            # pour accéder à Secret Manager
```

Le service account de la VM doit avoir `roles/secretmanager.secretAccessor` :

```bash
SA=$(gcloud compute instances describe mtr --zone=us-east4-c --format='value(serviceAccounts[0].email)')
gcloud projects add-iam-policy-binding "$PROJECT" \
  --member="serviceAccount:$SA" --role=roles/secretmanager.secretAccessor
```

Le startup script (`deploy/startup-script.sh`) : installe Docker si besoin, écrit `.env` depuis
Secret Manager (+ `SESSION_END=16:00`, `SESSION_TZ=America/New_York`), puis `docker compose up -d`.

## 4. Fenêtre horaire (Cloud Scheduler)

Deux jobs qui démarrent/arrêtent la VM via l'API Compute (compte de service avec les droits `compute.instances.start/stop`) :

```bash
ZONE=us-east4-c
BASE="https://compute.googleapis.com/compute/v1/projects/$PROJECT/zones/$ZONE/instances/mtr"

gcloud scheduler jobs create http mtr-start \
  --schedule="0 7 * * 1-5" --time-zone="America/New_York" \
  --uri="$BASE/start" --http-method=POST \
  --oauth-service-account-email="SCHED_SA_EMAIL"

gcloud scheduler jobs create http mtr-stop \
  --schedule="10 16 * * 1-5" --time-zone="America/New_York" \
  --uri="$BASE/stop" --http-method=POST \
  --oauth-service-account-email="SCHED_SA_EMAIL"
```

- `0 7 * * 1-5` → démarre à 7h00, jours ouvrés. `10 16 * * 1-5` → éteint à 16h10.
- Le bot ayant flatté à 16h00 (clôture), l'arrêt VM à 16h10 est purement pour le coût.
  (Astuce : pour des fills plus sûrs, tu peux mettre `SESSION_END=15:55` — flatten juste avant la cloche.)

## 5. Monitoring / alertes

- Le bot **alerte sur Telegram** au démarrage, à l'arrêt, sur kill switch, halt, stream instable.
- Un redémarrage inattendu (crash) se voit via un « mtr started » hors horaire prévu.
- Docker `restart: on-failure` relance le conteneur en cas de crash pendant la fenêtre.
- Option : uptime/alerting GCP en plus.

## Mettre à jour le bot

Push sur `master` → l'image `:latest` est reconstruite. Au prochain démarrage de VM, le startup
script `docker pull` la dernière image. (Ou `docker compose pull && up -d` sur la VM.)
