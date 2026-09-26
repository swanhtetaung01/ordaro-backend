#!/usr/bin/env bash
# One time, on a new database: create the two roles and the private schema (db/bootstrap.sql),
# as the RDS master user, and write their generated passwords into deploy/.env.
#
#   ./bootstrap-database.sh
#
# It asks for the RDS endpoint and the master password. Neither is stored anywhere; the two new
# role passwords live only in deploy/.env (readable by you alone).
set -euo pipefail
cd "$(dirname "$0")"

read -rp "RDS endpoint (e.g. trillopos.xxxx.ap-southeast-1.rds.amazonaws.com): " HOST
PORT=5432
if [[ "$HOST" == *:* ]]; then
  PORT=${HOST##*:}
  HOST=${HOST%%:*}
fi
read -rp "Master username [postgres]: " MASTER
MASTER=${MASTER:-postgres}
read -rsp "Master password: " MASTER_PASSWORD
echo
read -rp "Database name [postgres]: " DATABASE
DATABASE=${DATABASE:-postgres}
SSLMODE=${TRILLOPOS_BOOTSTRAP_SSLMODE:-require}

OWNER_PASSWORD=$(openssl rand -hex 24)
APP_PASSWORD=$(openssl rand -hex 24)
DB_DIR="$(cd .. && pwd)/db"

echo "== creating trillopos_owner, trillopos_app and the trillopos schema"
docker run --rm -i -e PGPASSWORD="$MASTER_PASSWORD" -v "$DB_DIR:/db:ro" postgres:17 \
  psql "host=$HOST port=$PORT dbname=$DATABASE user=$MASTER sslmode=$SSLMODE" \
  -v ON_ERROR_STOP=1 -v owner_password="$OWNER_PASSWORD" -v app_password="$APP_PASSWORD" \
  -f /db/bootstrap.sql

echo "== writing deploy/.env"
[ -f .env ] || cp .env.example .env
chmod 600 .env
set_value() {
  if grep -q "^$1=" .env; then
    sed -i "s|^$1=.*|$1=$2|" .env
  else
    echo "$1=$2" >> .env
  fi
}
set_value TRILLOPOS_DB_URL "jdbc:postgresql://$HOST:$PORT/$DATABASE?sslmode=$SSLMODE"
set_value TRILLOPOS_OWNER_USER trillopos_owner
set_value TRILLOPOS_OWNER_PASSWORD "$OWNER_PASSWORD"
set_value TRILLOPOS_APP_USER trillopos_app
set_value TRILLOPOS_APP_PASSWORD "$APP_PASSWORD"

echo
echo "Done. The database has its roles; deploy/.env has their passwords."
echo "Now set TRILLOPOS_DOMAIN and TRILLOPOS_SIGNUP_CODE in deploy/.env, then run ./deploy.sh"
