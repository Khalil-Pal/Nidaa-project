#!/usr/bin/env bash
# Gate check G2 (and DEP-1): build a database from the migrations alone with
# Flyway, exactly as the application does on an empty database, then check
# that flyway_schema_history lists every migration file and nothing else.
#
# Usage: scripts/gate/fresh-db.sh [dbname]        (default: nidaa_gate)
# Env:   PGUSER (default postgres), PSQL (path to psql if not on PATH),
#        DB_PASSWORD (read from .env when unset)
set -u
DB="${1:-nidaa_gate}"
PGUSER="${PGUSER:-postgres}"
PSQL="${PSQL:-psql}"
cd "$(dirname "$0")/../.."
if [ -z "${DB_PASSWORD:-}" ] && [ -f .env ]; then
  DB_PASSWORD=$(grep -E '^DB_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
fi

status=0
pass() { printf "  PASS  %s\n" "$1"; }
fail() { printf "  FAIL  %s\n" "$1"; status=1; }

echo "== G2: fresh database '$DB' =="
"$PSQL" -U "$PGUSER" -d postgres -tAc "DROP DATABASE IF EXISTS $DB;" >/dev/null 2>&1
"$PSQL" -U "$PGUSER" -d postgres -tAc "CREATE DATABASE $DB;" >/dev/null || { echo "FAIL: cannot create $DB"; exit 1; }

echo "== G2/DEP-1: Flyway migrate (same files, same engine as the application) =="
if ./mvnw -q flyway:migrate -Dflyway.url="jdbc:postgresql://localhost:5432/$DB" \
     -Dflyway.user="$PGUSER" -Dflyway.password="${DB_PASSWORD:-}" >/tmp/flyway-$DB.log 2>&1; then
  pass "flyway:migrate on an empty database"
else
  fail "flyway:migrate (see /tmp/flyway-$DB.log)"; tail -20 "/tmp/flyway-$DB.log"; exit 1
fi

# every file must be in the history as a successful versioned migration, and vice versa
files=$(ls src/main/resources/db/migration/V*.sql | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tr '\n' ' ')
applied=$("$PSQL" -U "$PGUSER" -d "$DB" -tAc "SELECT version FROM flyway_schema_history WHERE success AND type='SQL' ORDER BY version::int" | tr -d '\r' | tr '\n' ' ')
if [ "$files" = "$applied" ]; then pass "flyway_schema_history matches the files: $applied"
else fail "history '$applied' differs from files '$files'"; fi
failed=$("$PSQL" -U "$PGUSER" -d "$DB" -tAc "SELECT count(*) FROM flyway_schema_history WHERE NOT success" | tr -d '\r')
[ "$failed" = "0" ] && pass "no failed migration rows" || fail "$failed failed migration rows"

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
