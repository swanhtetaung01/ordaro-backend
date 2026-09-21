#!/bin/sh
# Runs once, on the first start of an empty data volume (docker-entrypoint-initdb.d).
# bootstrap.sql lives outside initdb.d so the entrypoint does not also run it without variables.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v owner_password="$ORDARO_OWNER_PASSWORD" \
     -v app_password="$ORDARO_APP_PASSWORD" \
     -f /ordaro/bootstrap.sql
