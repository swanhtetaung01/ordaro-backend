#!/usr/bin/env bash
# Put the latest main of both repos live: pull, back up the database, rebuild, restart, check.
# The API applies any new database migration by itself as it starts.
#
#   ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"
# the web app is cloned beside this repo: trillopos-web (ordaro-web in older clones)
WEB_DIR="${ORDARO_WEB_DIR:-}"
if [ -z "$WEB_DIR" ]; then
  for candidate in ../../trillopos-web ../../ordaro-web; do
    if [ -d "$candidate" ]; then WEB_DIR="$candidate"; break; fi
  done
fi
if [ -z "$WEB_DIR" ]; then
  echo "The web app is missing: clone trillopos-web beside trillopos-backend (see README.md step 5)"
  exit 1
fi
export ORDARO_WEB_DIR="$WEB_DIR"

if [ ! -f .env ]; then
  echo "deploy/.env is missing: run ./bootstrap-database.sh first (see README.md)"
  exit 1
fi

# always main, whatever a clone checked out: GitHub's default branch is not main everywhere
update() {
  git -C "$1" fetch -q origin main
  git -C "$1" checkout -q main
  git -C "$1" merge -q --ff-only origin/main
  echo "   $(basename "$(cd "$1" && pwd)") @ $(git -C "$1" log --oneline -1)"
}

echo "== pulling the latest main of both repos"
update ..
update "$WEB_DIR"

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
