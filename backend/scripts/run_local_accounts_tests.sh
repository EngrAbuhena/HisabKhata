#!/usr/bin/env bash
# ============================================================================
# Run the accounts/security migration against a scratch local PostgreSQL 17
# database and execute the acceptance suite.
#
# The scratch database is dropped afterwards unless --keep is passed.
# Nothing here talks to Supabase staging.
# ============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS=$(ls "$REPO_ROOT"/supabase/migrations/*.sql | sort)
SHIM="$REPO_ROOT/tests/local/00_supabase_shim.sql"
TESTS=$(ls "$REPO_ROOT"/tests/local/*_test.sql | sort)
SCRATCH_DB="hisabkhata_accounts_test"
PG_BIN="/usr/lib/postgresql/17/bin"
LOG="/tmp/hisabkhata_accounts_tests.log"

KEEP=0
[[ "${1:-}" == "--keep" ]] && KEEP=1

fail() { echo "FAILED: $*" >&2; exit 1; }

[[ -n "$MIGRATIONS" ]] || fail "no migrations found"
[[ -f "$SHIM" ]] || fail "shim not found: $SHIM"
[[ -n "$TESTS" ]] || fail "no test files found"
[[ -x "$PG_BIN/psql" ]] || fail "PostgreSQL 17 client not found in $PG_BIN"

# ---------------------------------------------------------------- server ----
if ! sudo -u postgres "$PG_BIN/pg_isready" -h /tmp -q; then
    echo "Starting local PostgreSQL 17 (Unix socket only)…"
    # Debian keeps postgresql.conf in /etc; pass it explicitly because the data
    # directory has no copy.
    sudo -u postgres "$PG_BIN/pg_ctl" \
        -D /var/lib/postgresql/17/main \
        -o "-c config_file=/etc/postgresql/17/main/postgresql.conf -c listen_addresses='' -c unix_socket_directories=/tmp" \
        -l /tmp/hisabkhata_pg.log -w start >/dev/null
fi
sudo -u postgres "$PG_BIN/pg_isready" -h /tmp -q || fail "local PostgreSQL is not ready"

psql_admin() { sudo -u postgres "$PG_BIN/psql" -h /tmp -X -v ON_ERROR_STOP=1 "$@"; }

# The workspace is not traversable by the postgres account, so SQL files are
# fed through stdin (opened by this shell) instead of with -f.
psql_file() {
    local db="$1" file="$2"
    shift 2
    sudo -u postgres "$PG_BIN/psql" -h /tmp -X -v ON_ERROR_STOP=1 -d "$db" -q "$@" -f - < "$file"
}

# --------------------------------------------------------------- scratch ----
echo "Recreating scratch database ${SCRATCH_DB}…"
psql_admin -d postgres -q -c "drop database if exists ${SCRATCH_DB};" \
                            -c "create database ${SCRATCH_DB};"

echo "Applying local Supabase shim…"
psql_file "$SCRATCH_DB" "$SHIM" >/dev/null

for m in $MIGRATIONS; do
    echo "Applying migration $(basename "$m")…"
    psql_file "$SCRATCH_DB" "$m" >/dev/null
done

echo "Running acceptance suites (all changes rolled back)…"
: > "$LOG"
set +e
for t in $TESTS; do
    echo "--- $(basename "$t") ---" | tee -a "$LOG"
    psql_file "$SCRATCH_DB" "$t" 2>&1 | tee -a "$LOG"
    if [[ ${PIPESTATUS[0]} -ne 0 ]]; then STATUS=1; break; fi
done
STATUS=${STATUS:-0}
set -e

PASSES=$(grep -c 'NOTICE:  ok' "$LOG" || true)

if [[ $STATUS -ne 0 ]]; then
    echo "ACCOUNTS TESTS FAILED (${PASSES} assertions passed before the failure)" >&2
    [[ $KEEP -eq 1 ]] || psql_admin -d postgres -q -c "drop database if exists ${SCRATCH_DB};" >/dev/null
    exit 1
fi

grep -q 'ALL ACCOUNTS FOUNDATION TESTS PASSED' "$LOG" \
    || fail "accounts suite exited cleanly without its completion marker"
grep -q 'ALL PRIVATE LEDGER TESTS PASSED' "$LOG" \
    || fail "ledger suite exited cleanly without its completion marker"
grep -q 'ALL NOTES TESTS PASSED' "$LOG" \
    || fail "notes suite exited cleanly without its completion marker"
grep -q 'ALL ATOMIC LINK TESTS PASSED' "$LOG" \
    || fail "atomic link suite exited cleanly without its completion marker"

echo "ACCOUNTS TESTS PASSED (${PASSES} assertions)"

if [[ $KEEP -eq 0 ]]; then
    psql_admin -d postgres -q -c "drop database if exists ${SCRATCH_DB};" >/dev/null
fi
