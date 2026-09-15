#!/usr/bin/env bash
#
# Restores a backup OVER THE LIVE DATABASE, VPS edition. This replaces the current data with
# the backup's data. The Mac version is scripts/restore.sh.
#
# Usage:  deploy/restore.sh                  (restores the most recent dump)
#         deploy/restore.sh <path-to.dump>   (restores a specific one, e.g. one copied from the Mac)
#
# Stops the app container itself, restores, and starts it again. It does NOT restore ./data —
# untar the matching data-*.tar.gz from backups/nightly/ into the project folder if the photos
# and product images are needed too.

set -euo pipefail

cd "$(dirname "$0")/.."

BACKUP_DIR="$PWD/backups"
db() { docker compose exec -T db "$@"; }

DUMP="${1:-$(ls -t "$BACKUP_DIR"/supreme-*.dump "$BACKUP_DIR"/nightly/supreme-*.dump 2>/dev/null | head -1 || true)}"
[ -n "$DUMP" ] && [ -f "$DUMP" ] || { echo "No backup file found. Looked in $BACKUP_DIR and $BACKUP_DIR/nightly"; exit 1; }

echo "About to restore:   $DUMP"
echo "Into the LIVE database: supreme (the db container)"
echo ""
echo "This REPLACES the current data. Anything recorded since that backup will be lost."
read -r -p "Type  yes  to continue: " CONFIRM
[ "$CONFIRM" = "yes" ] || { echo "Cancelled. Nothing was changed."; exit 1; }

db pg_isready -U supreme -d supreme -q || { echo "The db container is not running. docker compose up -d db, then run this again."; exit 1; }

echo "Stopping the app..."
docker compose stop app

echo "Restoring..."
# --clean --if-exists drops the old objects first, so the restore is a replacement, not a merge.
db pg_restore -U supreme -d supreme --clean --if-exists --no-owner --no-privileges < "$DUMP" 2>&1 \
  | grep -v "^pg_restore: warning" || true

echo ""
echo "Restore finished. Figures now in the database:"
db psql -U supreme -d supreme \
  -c "select business_date, count(*) as bills, sum(total_amount) as gross
      from bill where status='CLOSED' group by business_date order by business_date desc limit 5;"

echo ""
echo "Starting the app again..."
docker compose start app
echo "Done. Watch it come up with:  docker compose logs -f app"
