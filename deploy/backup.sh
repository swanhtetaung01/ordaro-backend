#!/usr/bin/env bash
# A complete copy of the database as one file, in ~/ordaro-backups, kept for 14 days.
# RDS keeps its own automatic backups (restore to any minute of the last 7 days); this one is a
# file you hold yourself — before a deploy, and every night from cron (README step 9).
#
#   ./backup.sh
set -euo pipefail
cd "$(dirname "$0")"

value() { sed -n "s/^$1=//p" .env | tail -1; }
URL=$(value ORDARO_DB_URL)                  # jdbc:postgresql://host:5432/ordaro?sslmode=require
ADDRESS=${URL#jdbc:postgresql://}
ADDRESS=${ADDRESS%%/*}
HOST=${ADDRESS%%:*}
PORT=5432
[[ "$ADDRESS" == *:* ]] && PORT=${ADDRESS##*:}
DATABASE=${URL#*//*/}
DATABASE=${DATABASE%%\?*}
SSLMODE=require
[[ "$URL" == *sslmode=* ]] && SSLMODE=$(echo "$URL" | sed -n 's/.*sslmode=\([a-z-]*\).*/\1/p')

DIR="${ORDARO_BACKUP_DIR:-$HOME/ordaro-backups}"
mkdir -p "$DIR"
FILE="ordaro-$(date +%Y%m%d-%H%M%S).dump"

# as the owner: it owns every table, and row-level security does not apply to a table's owner
docker run --rm -e PGPASSWORD="$(value ORDARO_OWNER_PASSWORD)" -v "$DIR:/backup" postgres:17 \
  pg_dump "host=$HOST port=$PORT dbname=$DATABASE user=$(value ORDARO_OWNER_USER) sslmode=$SSLMODE" \
  --format=custom --file="/backup/$FILE"

find "$DIR" -name 'ordaro-*.dump' -mtime +14 -delete
echo "backup: $DIR/$FILE ($(du -h "$DIR/$FILE" | cut -f1))"
