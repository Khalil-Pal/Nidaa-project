#!/usr/bin/env bash
# Gate check G2: build a database from the migrations alone, in version order,
# then re-run every idempotent migration and check exit codes.
#
# Usage: scripts/gate/fresh-db.sh [dbname]        (default: nidaa_gate)
# Env:   PGUSER (default postgres), PSQL (path to psql if not on PATH)
#
# V1..V8 are shipped, non-idempotent migrations (CREATE TABLE without IF NOT
# EXISTS; V4 must never be re-run on a populated database). Re-run checks
# therefore cover V9 onwards, which are written to be safe to repeat.
set -u
DB="${1:-nidaa_gate}"
PGUSER="${PGUSER:-postgres}"
PSQL="${PSQL:-psql}"
cd "$(dirname "$0")/../.."

status=0
run() { "$PSQL" -U "$PGUSER" -d "$1" -v ON_ERROR_STOP=1 -q -f "$2" >/dev/null 2>&1; }

echo "== G2: fresh database '$DB' =="
"$PSQL" -U "$PGUSER" -d postgres -tAc "DROP DATABASE IF EXISTS $DB;" >/dev/null 2>&1
"$PSQL" -U "$PGUSER" -d postgres -tAc "CREATE DATABASE $DB;" >/dev/null || { echo "FAIL: cannot create $DB"; exit 1; }

# Version order, not glob order (a plain glob sorts V10 before V2).
mapfile -t FILES < <(ls database/migrations/V*.sql | sort -t V -k2 -n)
for f in "${FILES[@]}"; do
  if run "$DB" "$f"; then printf "  PASS  apply   %s\n" "$(basename "$f")"
  else printf "  FAIL  apply   %s\n" "$(basename "$f")"; status=1; fi
done

echo "== G2: re-run idempotent migrations (V9+) =="
for f in "${FILES[@]}"; do
  v=$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/')
  [ "$v" -lt 9 ] && continue
  if run "$DB" "$f"; then printf "  PASS  rerun   %s (exit 0)\n" "$(basename "$f")"
  else printf "  FAIL  rerun   %s\n" "$(basename "$f")"; status=1; fi
done

echo "== G3: trigger / function / view inventory of '$DB' =="
"$PSQL" -U "$PGUSER" -d "$DB" -tAc "
SELECT 'trigger  ' || event_object_table || '.' || trigger_name || ' ' || action_timing || ' ' || event_manipulation
FROM information_schema.triggers WHERE trigger_schema='public'
UNION ALL
SELECT 'function ' || routine_name || ' (' || routine_type || ')' FROM information_schema.routines WHERE routine_schema='public'
UNION ALL
SELECT 'view     ' || table_name FROM information_schema.views WHERE table_schema='public'
ORDER BY 1;" | sed 's/^/  /'

exit $status
