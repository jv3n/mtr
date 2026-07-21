#!/usr/bin/env bash
# One-shot GCP provisioning for the scheduled paper deployment (see docs/deploy.md).
# Prereq: `gcloud auth login` done. Run from the repo root.
#
#   PROJECT_ID=mtr-bot-260721 BILLING_ACCOUNT=XXXXXX-XXXXXX-XXXXXX bash deploy/gcp-setup.sh setup
#   PROJECT_ID=mtr-bot-260721 bash deploy/gcp-setup.sh vm      # after making the GHCR image public
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-east4}"
ZONE="${ZONE:-us-east4-c}"
IMAGE="ghcr.io/jv3n/mtr:latest"
SECRET_KEYS=(TRADEZERO_API_KEY TRADEZERO_API_SECRET TRADEZERO_ENV MASSIVE_API_KEY MASSIVE_WS_URL TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID)

setup() {
  local billing="${BILLING_ACCOUNT:?set BILLING_ACCOUNT}"

  echo "== project =="
  if gcloud projects describe "$PROJECT_ID" >/dev/null 2>&1; then
    echo "  project $PROJECT_ID already accessible"
  else
    gcloud projects create "$PROJECT_ID" --name=mtr-bot # fails loudly if the ID is taken globally
  fi
  gcloud billing projects link "$PROJECT_ID" --billing-account="$billing"
  gcloud config set project "$PROJECT_ID"

  echo "== APIs =="
  gcloud services enable compute.googleapis.com secretmanager.googleapis.com \
    cloudscheduler.googleapis.com --project "$PROJECT_ID"

  echo "== secrets (from .env) =="
  [[ -f .env ]] || { echo "  no .env found"; return; }
  while IFS='=' read -r k v; do
    v="${v%$'\r'}"
    [[ " ${SECRET_KEYS[*]} " == *" $k "* ]] || continue
    [[ -n "$v" ]] || continue
    if gcloud secrets describe "mtr-$k" --project "$PROJECT_ID" >/dev/null 2>&1; then
      printf '%s' "$v" | gcloud secrets versions add "mtr-$k" --data-file=- --project "$PROJECT_ID" >/dev/null
      echo "  updated mtr-$k"
    else
      printf '%s' "$v" | gcloud secrets create "mtr-$k" --data-file=- --project "$PROJECT_ID" >/dev/null
      echo "  created mtr-$k"
    fi
  done < .env
  echo "setup done."
}

vm() {
  echo "== VM =="
  gcloud compute instances create mtr --project "$PROJECT_ID" --zone="$ZONE" \
    --machine-type=e2-small --image-family=cos-stable --image-project=cos-cloud \
    --scopes=cloud-platform \
    --metadata-from-file=startup-script=deploy/startup-script.sh

  local sa
  sa="$(gcloud compute instances describe mtr --zone="$ZONE" --project "$PROJECT_ID" \
        --format='value(serviceAccounts[0].email)')"
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$sa" --role=roles/secretmanager.secretAccessor >/dev/null

  echo "== scheduler service account =="
  local sched="mtr-scheduler@$PROJECT_ID.iam.gserviceaccount.com"
  gcloud iam service-accounts create mtr-scheduler --project "$PROJECT_ID" \
    --display-name="mtr scheduler" 2>/dev/null || true
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$sched" --role=roles/compute.instanceAdmin.v1 >/dev/null

  echo "== scheduler jobs (New York time) =="
  local base="https://compute.googleapis.com/compute/v1/projects/$PROJECT_ID/zones/$ZONE/instances/mtr"
  gcloud scheduler jobs create http mtr-start --project "$PROJECT_ID" --location="$REGION" \
    --schedule="0 7 * * 1-5" --time-zone="America/New_York" \
    --uri="$base/start" --http-method=POST --oauth-service-account-email="$sched" 2>/dev/null || true
  gcloud scheduler jobs create http mtr-stop --project "$PROJECT_ID" --location="$REGION" \
    --schedule="10 16 * * 1-5" --time-zone="America/New_York" \
    --uri="$base/stop" --http-method=POST --oauth-service-account-email="$sched" 2>/dev/null || true
  echo "vm + scheduler done. (Ensure the GHCR image is public so the VM can pull it.)"
}

case "${1:-setup}" in
  setup) setup ;;
  vm) vm ;;
  all) setup; vm ;;
  *) echo "usage: $0 {setup|vm|all}"; exit 1 ;;
esac
