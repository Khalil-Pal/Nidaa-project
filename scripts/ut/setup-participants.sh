#!/usr/bin/env bash
# UT-1 session setup: the accounts the task scripts expect and the queue they need.
#
# Usage:  BASE=http://127.0.0.1:8081 DB=nidaa_ut scripts/ut/setup-participants.sh
#
# Needs the application running against a database built by scripts/gate/fresh-db.sh,
# a mail sink on 1025 (registration is transactional with the verification e-mail),
# and psql, for the verification code and the first administrator — the two things
# the API deliberately does not hand out. Start the application with a wider auth
# budget (RATELIMIT_AUTH_PER_MINUTE=50, as UT-1-user-testing.md section 3 shows):
# this script makes a dozen authentication calls in a few seconds and the default
# budget would throttle it.
#
# Re-run it between participants, after rebuilding the database, so every session
# starts from the same state. It refuses to run against a database that already has
# users, so it cannot quietly half-apply itself on top of a used one.
set -u

BASE="${BASE:-http://127.0.0.1:8081}"
DB="${DB:-nidaa_ut}"
PGUSER="${PGUSER:-postgres}"
PSQL="${PSQL:-psql}"
PW='Ut-Session-2026!'
ADMIN_PW='Ut-Admin-2026'          # the bcrypt hash below is of this password

sql() { "$PSQL" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '\r'; }
api() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
json() { curl -s "$@"; }
tok() {
  local body
  body=$(json -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$2\"}")
  echo "$body" | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null && return 0
  echo "could not sign in as $1: $body" >&2
  echo "(a rate-limit message here means the application needs RATELIMIT_AUTH_PER_MINUTE=50)" >&2
  exit 1
}

if [ "$(sql 'select count(*) from users')" != "0" ]; then
  echo "refusing to run: $DB already has users. Rebuild it first:"
  echo "  scripts/gate/fresh-db.sh $DB"
  exit 1
fi

register() {  # email role fullname -> verified account (providers still need approval)
  local email="$1" role="$2" name="$3"
  api -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"fullName\":\"$name\",\"email\":\"$email\",\"password\":\"$PW\",\"phone\":\"+1$RANDOM$RANDOM\",\"role\":\"$role\"}" >/dev/null
  api -X POST "$BASE/api/auth/register/verify" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"code\":\"$(sql "select code from pending_registrations where email='$email'")\"}" >/dev/null
}

approve() {
  api -X PUT "$BASE/api/admin/approve/$(sql "select user_id from users where email='$1'")" \
    -H "Authorization: Bearer $ADMIN_TOKEN" >/dev/null
}

# The first administrator goes in directly, the way database/migrations/README.md
# documents: ADMIN cannot be self-registered (S-1).
sql "INSERT INTO users (email,password_hash,full_name,role,is_verified,is_active,is_locked)
     VALUES ('ut-admin@example.test','\$2a\$10\$5FwMYk/oQyzCIs1YU2VIoeTXqQ9Du/3PFMqb5GB01/cKkKFSFpa0.',
             'Session Facilitator','ADMIN',true,true,false)" >/dev/null
ADMIN_TOKEN=$(tok ut-admin@example.test "$ADMIN_PW")

register ut-bene1@example.test beneficiary "Amal Haddad"
register ut-bene2@example.test beneficiary "Karim Nasser"
register ut-vol1@example.test  volunteer   "Dina Farah"
register ut-vol2@example.test  volunteer   "Omar Saleh"
register ut-psy1@example.test  psychologist "Dr Layla Mansour"
register ut-queue@example.test beneficiary "Queue Filler"

approve ut-vol1@example.test
approve ut-vol2@example.test
approve ut-psy1@example.test

# The psychologist is professionally verified but stays OFF duty: going on duty is
# their first task, and approval deliberately leaves is_on_duty false.
api -X PUT "$BASE/api/admin/psychologists/$(sql "select user_id from users where email='ut-psy1@example.test'")/verification" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"verified":true}' >/dev/null

# The queue the volunteer scripts need: eight requests over three settlements,
# two of them critical, filed by the queue account so the participants' own
# requests are the only ones they own.
QUEUE_TOKEN=$(tok ut-queue@example.test "$PW")
pending() {  # title type urgency people children lat lon
  api -X POST "$BASE/api/help-requests" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $QUEUE_TOKEN" \
    -d "{\"title\":\"$1\",\"description\":\"Session fixture.\",\"helpType\":\"$2\",\"urgencyLevel\":\"$3\",
         \"peopleCount\":$4,\"hasChildren\":$5,\"latitude\":$6,\"longitude\":$7}" >/dev/null
}
pending "Insulin for a diabetic grandmother" MEDICAL  CRITICAL 1 false 55.61 37.41
pending "Baby formula and nappies"           FOOD     CRITICAL 3 true  55.58 37.43
pending "Food for the week"                  FOOD     HIGH     5 true  55.62 37.38
pending "Blankets, the heating is off"       SHELTER  HIGH     2 false 56.31 37.59
pending "Drinking water, the well is dry"    WATER    MEDIUM   6 true  56.28 37.62
pending "Winter coats for two children"      CLOTHING MEDIUM   4 true  55.71 38.41
pending "Help moving to a shelter"           SHELTER  MEDIUM   3 false 55.69 38.38
pending "Blood-pressure medication"          MEDICAL  LOW      1 false 55.60 37.44

# An anonymous psychological case already assigned to the psychologist, with no
# session recorded: psychologist task 3 records the first one.
BENE2_TOKEN=$(tok ut-bene2@example.test "$PW")
CASE=$(json -X POST "$BASE/api/psychological-requests" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BENE2_TOKEN" \
  -d '{"supportType":"INDIVIDUAL","category":"GRIEF","preferredFormat":"VIDEO","isAnonymous":true,
       "description":"Session fixture: assigned case."}' |
  python -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
PSY_TOKEN=$(tok ut-psy1@example.test "$PW")
api -X PUT "$BASE/api/psychological-requests/$CASE/accept" -H "Authorization: Bearer $PSY_TOKEN" >/dev/null

# And one waiting case for psychologist task 2.
api -X POST "$BASE/api/psychological-requests" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BENE2_TOKEN" \
  -d '{"supportType":"INDIVIDUAL","category":"ANXIETY","preferredFormat":"CHAT",
       "description":"Session fixture: waiting case."}' >/dev/null

cat <<SHEET

UT-1 session accounts on $BASE ($DB)

  beneficiary   ut-bene1@example.test   $PW
  beneficiary   ut-bene2@example.test   $PW    (owns the psychological cases)
  volunteer     ut-vol1@example.test    $PW
  volunteer     ut-vol2@example.test    $PW
  psychologist  ut-psy1@example.test    $PW    (verified, OFF duty)
  administrator ut-admin@example.test   $ADMIN_PW

  $(sql "select count(*) from help_requests where status='PENDING'") pending help requests, $(sql "select count(*) from psychological_requests where status='PENDING'") waiting case, $(sql "select count(*) from psychological_requests where status='ASSIGNED'") assigned case

These are throwaway credentials for a throwaway database. Print this block for
the facilitator's sheet; do not commit it anywhere.

Between beneficiary task 2 and task 3, accept the participant's food request as
ut-vol1 so that task 3 has something to find.
SHEET
