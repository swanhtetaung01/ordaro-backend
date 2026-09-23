#!/usr/bin/env bash
# Put the latest main of both repos live: pull, back up the database, rebuild, restart, check.
# The API applies any new database migration by itself as it starts.
#
#   ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"
WEB_DIR="${ORDARO_WEB_DIR:-../../ordaro-web}"

if [ ! -f .env ]; then
  echo "deploy/.env is missing: run ./bootstrap-database.sh first (see README.md)"
  exit 1
fi

echo "== pulling the latest code"
git -C .. pull --ff-only
git -C "$WEB_DIR" pull --ff-only

if docker compose ps --status running --services 2>/dev/null | grep -qx backend; then
  echo "== backing up the database before anything changes"
  bash ./backup.sh
fi

echo "== building (a few minutes on a small server)"
docker compose build

echo "== starting"
docker compose up -d

echo "== waiting for the API"
for attempt in $(seq 1 60); do
  if curl -fs http://127.0.0.1:8080/actuator/health >/dev/null; then
    echo "the API is up"
    break
  fi
  if [ "$attempt" = 60 ]; then
    echo "The API did not come up. Look at:  docker compose logs --tail 100 backend"
    exit 1
  fi
  sleep 5
done

docker compose ps
docker image prune -f >/dev/null
echo "== live at https://$(sed -n 's/^ORDARO_DOMAIN=//p' .env | tail -1)"
