#!/usr/bin/env bash
#
# Proves the latest backup is actually restorable, VPS edition. The Mac version is
# scripts/verify-backup.sh.
#
# Restores the most recent dump into a THROWAWAY database inside the db container, checks
# that real figures come back, then drops the throwaway. It never touches the live database.
#
# An untested backup is a hypothesis. Run this after any change to the backup setup, and once
# a month regardless.

set -euo pipefail

cd "$(dirname "$0")/.."

BACKUP_DIR="$PWD/backups"
SCRATCH_DB="supreme_restore_check"
db() { docker compose exec -T db "$@"; }

say() { printf '%s\n' "$*"; }

DUMP="$(ls -t "$BACKUP_DIR"/supreme-*.dump "$BACKUP_DIR"/nightly/supreme-*.dump 2>/dev/null | head -1 || true)"
[ -n "$DUMP" ] || { say "No backup found in $BACKUP_DIR. Run deploy/backup.sh first."; exit 1; }

say "Checking:  $DUMP"
say ""

db pg_isready -U supreme -d supreme -q || { say "The db container is not running. docker compose up -d db, then run this again."; exit 1; }

# The scratch database is dropped and recreated every run, so a previous failed check cannot
# leave a stale result behind that looks like a pass.
db dropdb -U supreme --if-exists "$SCRATCH_DB" 2>/dev/null || true
db createdb -U supreme "$SCRATCH_DB"

# The compose user is the image's superuser, so the extension needs no ceremony.
db psql -U supreme -d "$SCRATCH_DB" -q -c "CREATE EXTENSION IF NOT EXISTS btree_gist;" >/dev/null

# --no-owner because the scratch database is owned by supreme, not by whoever owned the original.
db pg_restore -U supreme -d "$SCRATCH_DB" --no-owner --no-privileges < "$DUMP" >/dev/null 2>&1 || true

TABLES=$(db psql -U supreme -d "$SCRATCH_DB" -tAc \
    "select count(*) from information_schema.tables where table_schema='public';")

say "Restored into a scratch database."
say "  tables restored : $TABLES"

say ""
say "Figures read back OUT OF THE RESTORE (not the live database):"
db psql -U supreme -d "$SCRATCH_DB" \
  -c "select count(*) as closed_bills,
             sum(total_amount) as gross,
             sum(total_cost)   as cost,
             sum(total_amount - total_cost) as profit
      from bill where status = 'CLOSED';" \
  -c "select receipt_no, business_date, total_amount as gross, total_cost as cost,
             (total_amount - total_cost) as profit
      from bill where status='CLOSED' and total_amount = 630.00
      order by receipt_no limit 1;"

TRACE=$(db psql -U supreme -d "$SCRATCH_DB" -tAc \
    "select count(*) from bill
     where status='CLOSED' and total_amount=630.00 and total_cost=157.50
       and (total_amount - total_cost)=472.50;")

db dropdb -U supreme --if-exists "$SCRATCH_DB"

say ""
if [ "$TABLES" -lt 20 ]; then
    say "FAILED - only $TABLES tables came back. The live schema has more than that; this dump did"
    say "not restore cleanly. Do NOT trust it."
    exit 1
fi
if [ "$TRACE" -ge 1 ]; then
    say "PASS - the worked trace came back intact: gross 630.00, cost 157.50, profit 472.50."
    say "This backup is restorable."
    exit 0
else
    say "WARNING - the restore worked, but the reference bill (630.00 / 157.50 / 472.50) was not"
    say "found in it. That is expected if the trace bill is older than this backup's retention,"
    say "or was never taken on this database. Check the totals printed above look like a real night."
    exit 0
fi
