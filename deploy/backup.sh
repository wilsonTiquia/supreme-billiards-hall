#!/usr/bin/env bash
#
# Backup of the Supreme Billiard Hall POS, VPS edition. Runs on the HOST, from cron, against
# the containers in compose.yaml. The Mac version is scripts/backup.sh; this one has the same
# shape so the person who knows one knows the other.
#
# Every 15 minutes:   deploy/backup.sh            dump only, kept 48 hours
# Once a night:       deploy/backup.sh --nightly  dump + ./data (photos, images), kept 14 days
#
# The dump is ~140 KB, which is why it runs every quarter hour: the most the hall can lose to
# a dead VPS is fifteen minutes of trade, provided the offsite copy is configured. Run it by
# hand any time, from the project folder:   deploy/backup.sh
#
# Holds no password. The database is reached through `docker compose exec` inside the db
# container, where the superuser connects over the local socket, and the offsite remote is
# named in .env. That is why this file can be committed and scripts/backup.sh cannot.

set -euo pipefail

cd "$(dirname "$0")/.."

# ===================================================================
# CONFIGURE ME — in .env, not here
# ===================================================================
#   RCLONE_REMOTE   the offsite copy, e.g. gdrive:SupremeBackups. Empty = one machine only.
[ -f .env ] && set -a && . ./.env && set +a
RCLONE_REMOTE="${RCLONE_REMOTE:-}"

BACKUP_DIR="$PWD/backups"
NIGHTLY_DIR="$BACKUP_DIR/nightly"
DATA_DIR="$PWD/data"

# How long each kind is kept, on the host and on the remote.
QUARTER_HOURLY_KEEP_MINUTES=$((48 * 60))
NIGHTLY_KEEP_DAYS=14

# ===================================================================
# Below here you should not need to change anything.
# ===================================================================

NIGHTLY=0
[ "${1:-}" = "--nightly" ] && NIGHTLY=1

LOG_FILE="$BACKUP_DIR/backup.log"
STAMP="$(date +%Y%m%d-%H%M%S)"
DUMP_NAME="supreme-$STAMP.dump"
DATA_NAME="data-$STAMP.tar.gz"
OUT_DIR="$BACKUP_DIR"
[ "$NIGHTLY" = 1 ] && OUT_DIR="$NIGHTLY_DIR"

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG_FILE"; }
fail() { log "FAILED: $*"; exit 1; }
db() { docker compose exec -T db "$@"; }

mkdir -p "$BACKUP_DIR" "$NIGHTLY_DIR"
log "----- backup starting ($([ "$NIGHTLY" = 1 ] && echo nightly || echo quarter-hourly)) -----"

db pg_isready -U supreme -d supreme -q \
  || fail "the database container is not running, so there is nothing to back up. docker compose up -d, then run this again."

# --format=custom so pg_restore can rebuild selectively and so the file is compressed.
db pg_dump -U supreme --format=custom supreme > "$OUT_DIR/$DUMP_NAME" \
  || fail "pg_dump could not read the database."

# An unreadable dump is worse than no dump, because you would trust it. Check it parses —
# through the same container, so the pg_restore doing the checking is the one that would do
# the restoring.
db pg_restore --list < "$OUT_DIR/$DUMP_NAME" > /dev/null \
  || fail "the dump file is unreadable. Do NOT trust this backup."

DUMP_SIZE=$(du -h "$OUT_DIR/$DUMP_NAME" | cut -f1)
log "database dumped: $DUMP_NAME ($DUMP_SIZE)"

if [ "$NIGHTLY" = 1 ]; then
    if [ -d "$DATA_DIR" ]; then
        tar -czf "$OUT_DIR/$DATA_NAME" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")" \
          || fail "could not archive the app's data folder."
        log "data folder archived: $DATA_NAME"
        # Counted per folder, because "0 payment photos" and "0 product images" are different
        # facts and only one of them is ever normal.
        for sub in payment-photos product-images; do
            if [ -d "$DATA_DIR/$sub" ]; then
                COUNT=$(find "$DATA_DIR/$sub" -type f | wc -l | tr -d ' ')
                log "  $sub: $COUNT file(s)"
            else
                log "  $sub: no folder yet (normal before the first one is saved)"
            fi
        done
    else
        log "no data folder yet, skipping (this is normal before the first photo or image)"
    fi
fi

if [ -n "$RCLONE_REMOTE" ]; then
    REMOTE_DIR="$RCLONE_REMOTE"
    [ "$NIGHTLY" = 1 ] && REMOTE_DIR="$RCLONE_REMOTE/nightly"
    rclone copy "$OUT_DIR/$DUMP_NAME" "$REMOTE_DIR/" || fail "could not copy the dump offsite."
    [ -f "$OUT_DIR/$DATA_NAME" ] && { rclone copy "$OUT_DIR/$DATA_NAME" "$REMOTE_DIR/" || fail "could not copy the data archive offsite."; }
    log "copied to the second location: $REMOTE_DIR"
else
    log "WARNING: no second location configured. This backup exists on ONE machine only."
fi

# Prune, oldest first. Quarter-hourly dumps by the minute, nightly by the day.
PRUNED=0
while IFS= read -r old; do
    rm -f "$old"; PRUNED=$((PRUNED + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 -name 'supreme-*.dump' -mmin +"$QUARTER_HOURLY_KEEP_MINUTES" 2>/dev/null)
while IFS= read -r old; do
    rm -f "$old"; PRUNED=$((PRUNED + 1))
done < <(find "$NIGHTLY_DIR" -maxdepth 1 \( -name 'supreme-*.dump' -o -name 'data-*.tar.gz' \) -mtime +"$NIGHTLY_KEEP_DAYS" 2>/dev/null)
if [ -n "$RCLONE_REMOTE" ]; then
    rclone delete --min-age "${QUARTER_HOURLY_KEEP_MINUTES}m" --include 'supreme-*.dump' --max-depth 1 "$RCLONE_REMOTE" 2>/dev/null || true
    rclone delete --min-age "${NIGHTLY_KEEP_DAYS}d" --max-depth 1 "$RCLONE_REMOTE/nightly" 2>/dev/null || true
fi
log "pruned $PRUNED file(s) (quarter-hourly older than 48h, nightly older than $NIGHTLY_KEEP_DAYS days)"

KEPT=$(find "$BACKUP_DIR" "$NIGHTLY_DIR" -maxdepth 1 -name 'supreme-*.dump' | wc -l | tr -d ' ')
log "done. $KEPT database backup(s) on hand."
log "----- backup finished -----"
