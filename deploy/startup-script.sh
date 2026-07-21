#!/usr/bin/env bash
# GCE VM startup script (paste into the instance "startup-script" metadata).
# Fetches secrets from Secret Manager into .env and runs the bot via docker compose.
# The VM is started/stopped by Cloud Scheduler (trading window) — see docs/deploy.md.
set -euo pipefail

APP_DIR=/opt/mtr
IMAGE=ghcr.io/jv3n/mtr:latest

# Docker (Container-Optimized OS already has it; on Ubuntu install if missing).
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh

mkdir -p "$APP_DIR/data"
cd "$APP_DIR"

# Minimal compose file.
cat > docker-compose.yml <<'YML'
services:
  mtr:
    image: ghcr.io/jv3n/mtr:latest
    env_file: .env
    volumes:
      - ./data:/app/data
    restart: on-failure
    stop_grace_period: 40s
YML

# Pull secrets from Secret Manager (secret names: mtr-<VAR>).
: > .env
for v in TRADEZERO_API_KEY TRADEZERO_API_SECRET TRADEZERO_ENV \
         MASSIVE_API_KEY MASSIVE_WS_URL TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID; do
  val="$(gcloud secrets versions access latest --secret="mtr-$v" 2>/dev/null || true)"
  [ -n "$val" ] && echo "$v=$val" >> .env
done

# Trading window (New York time). Bot flattens + stops at SESSION_END (US close).
echo "SESSION_END=16:00" >> .env
echo "SESSION_TZ=America/New_York" >> .env

docker pull "$IMAGE"
docker compose up -d
