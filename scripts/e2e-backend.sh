#!/usr/bin/env bash
#
# Starts a THROWAWAY POS instance for the browser tests: port 8081, its own database, its own
# photo folders. Playwright starts this itself (frontend/playwright.config.ts, webServer), so
# you normally never run it by hand.
#
# It refuses to touch the till. The same four refusals are in the Playwright config, which is
# what stops a run before a browser or a JVM exists; they are repeated here because this script
# can be run on its own, and a guard that only exists in the caller is not a guard.
#
# Read docs/RUNBOOK.md, "Never point the browser tests at the trading database", for why.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DB_HOST="${E2E_DB_HOST:-localhost}"
DB_PORT="${E2E_DB_PORT:-5432}"
DB_NAME="${E2E_DB_NAME:-supreme_e2e}"
DB_USER="${E2E_DB_USERNAME:-supreme}"
DB_PASS="${E2E_DB_PASSWORD:-supreme}"
API_PORT="${E2E_API_PORT:-8081}"
OWNER_PASSWORD="${E2E_OWNER_PASSWORD:-e2e-owner-password}"

# The stamp that says "this database was made to be destroyed". Written as a database comment
# after every create, and read before every drop. See guard 4.
MARKER='supreme-e2e-scratch: created by scripts/e2e-backend.sh, safe to destroy'

PG_BIN="/Library/PostgreSQL/18/bin"
PSQL="${PSQL:-$(command -v psql || echo "$PG_BIN/psql")}"
CREATEDB="${CREATEDB:-$(command -v createdb || echo "$PG_BIN/createdb")}"
DROPDB="${DROPDB:-$(command -v dropdb || echo "$PG_BIN/dropdb")}"
export PGPASSWORD="$DB_PASS"

refuse() {
    printf '\nREFUSING TO RUN\n\n  %s\n\n' "$1" >&2
    printf 'The browser tests get their own instance and their own throwaway database.\n' >&2
    printf 'See docs/RUNBOOK.md — "Never point the browser tests at the trading database".\n\n' >&2
    exit 1
}

psql_scratch() { "$PSQL" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tAc "$1"; }

# 1. Never the till's port.
if [ "$API_PORT" = "8080" ]; then refuse "E2E_API_PORT is 8080. That is the till."; fi

# 2. Never the till's database, whatever else it is called.
if [ "$DB_NAME" = "supreme" ]; then refuse "E2E_DB_NAME is 'supreme'. That is the trading database."; fi

# 3. A scratch database says so in its name. Anything else is somebody's data until proven
#    otherwise, and this is cheaper than proving it.
case "$DB_NAME" in
    *_e2e) ;;
    *) refuse "E2E_DB_NAME '$DB_NAME' does not end in _e2e, so it is not a scratch database." ;;
esac

# 4. The check that does not depend on anyone naming things correctly: a database holding
#    payments is real money, whatever it is called. Allowed only if it carries our own marker,
#    which is how last night's scratch database (full of test payments) stays usable.
if "$PSQL" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -tAc \
        "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" 2>/dev/null | grep -q 1; then
    STAMP="$("$PSQL" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -tAc \
        "SELECT coalesce(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = '$DB_NAME'" \
        2>/dev/null | tr -d '[:space:]')"
    if [ "$STAMP" != "$(printf '%s' "$MARKER" | tr -d '[:space:]')" ]; then
        # No marker. Only safe if it holds no payments. A missing payment table is an empty
        # database, which is safe too.
        PAYMENTS="$(psql_scratch "SELECT count(*) FROM payment" 2>/dev/null || echo 0)"
        if [ "${PAYMENTS:-0}" -gt 0 ]; then
            refuse "Database '$DB_NAME' holds $PAYMENTS payment rows and is not marked as scratch. That is somebody's real data."
        fi
    fi
fi

echo "Rebuilding the scratch database '$DB_NAME'..."
"$DROPDB" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" --if-exists "$DB_NAME"
"$CREATEDB" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" "$DB_NAME"
"$PSQL" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -q -c \
    "COMMENT ON DATABASE \"$DB_NAME\" IS '$MARKER'"

# Photos and product pictures land here rather than in ~/SupremeData. Unset, the app writes
# test uploads into the folder the nightly backup archives.
SCRATCH_DIR="${TMPDIR:-/tmp}"
SCRATCH_DIR="${SCRATCH_DIR%/}/supreme-e2e"
PHOTO_DIR="$SCRATCH_DIR/payment-photos"
IMAGE_DIR="$SCRATCH_DIR/product-images"
mkdir -p "$PHOTO_DIR" "$IMAGE_DIR"

# Flyway builds the schema into the empty database on boot; AdminPasswordBootstrap turns the
# 'owner' login on from the variable below, because V9 left every seeded login disabled and
# there is no other way in. HELP.md, "Launch-day: setting the first login credentials".
#
# spring-boot:run and NOT package: packaging rewrites target/*.jar, which is the file the live
# 8080 JVM is running from. It stops at compile, so the frontend exec-plugin executions bound to
# prepare-package never fire either — which is why the SPA is served by vite, not from here.
cd "$PROJECT_DIR"
export DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
export DB_USERNAME="$DB_USER"
export DB_PASSWORD="$DB_PASS"
export SUPREME_BOOTSTRAP_ADMIN_PASSWORD="$OWNER_PASSWORD"

echo "Starting the scratch POS on $API_PORT against $DB_URL"
exec ./mvnw -q spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=$API_PORT --supreme.payment-photo.path=$PHOTO_DIR --supreme.product-image.path=$IMAGE_DIR"
