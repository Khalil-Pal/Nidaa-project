#!/usr/bin/env bash
# Gate checks G4/G5: re-verify every task's acceptance criterion through the API,
# the way a client hits it. Prints PASS/FAIL per check with evidence and exits
# non-zero on any FAIL.
#
# Prerequisites
#   * the application running against a FRESH gate database (scripts/gate/fresh-db.sh)
#     with RATELIMIT_AUTH_PER_MINUTE=50 (the flow needs ~20 auth calls; S-9 is
#     checked from a second loopback address with 51 calls)
#   * psql access to that database (the verification codes are read from it, and the
#     first administrator is inserted the documented way)
#
# Usage
#   BASE=http://[::1]:8081 BASE_ALT=http://127.0.0.1:8081 DB=nidaa_gate \
#   PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe" JWT_SECRET=... \
#   bash scripts/gate/acceptance.sh
set -u
BASE="${BASE:-http://[::1]:8081}"
BASE_ALT="${BASE_ALT:-http://127.0.0.1:8081}"
DB="${DB:-nidaa_gate}"
PGUSER="${PGUSER:-postgres}"
PSQL="${PSQL:-psql}"
PASS=0; FAIL=0
STAMP=$(date +%s)
PW='Gate-Pass-2026!'

pass() { PASS=$((PASS+1)); printf "  PASS  %-6s %s\n" "$1" "$2"; }
fail() { FAIL=$((FAIL+1)); printf "  FAIL  %-6s %s\n" "$1" "$2"; }
check() { # id, description, actual, expected
  if [ "$3" = "$4" ]; then pass "$1" "$2 -> $3"; else fail "$1" "$2 -> got '$3', expected '$4'"; fi; }
sql() { "$PSQL" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '\r'; }
json() { python -c "import sys,json
d=json.load(sys.stdin)
for k in sys.argv[1].split('.'):
    d=d[int(k)] if k.isdigit() else d.get(k) if isinstance(d,dict) else None
    if d is None: break
print('' if d is None else d)" "$1"; }
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
body() { curl -s "$@"; }

# ---- accounts ------------------------------------------------------------------
register_and_verify() { # email role -> prints token (empty if pending approval)
  local email="$1" role="$2"
  curl -s -o /dev/null -X POST "$BASE/api/auth/register" -H "Content-Type: application/json" \
    -d "{\"fullName\":\"Gate $role\",\"email\":\"$email\",\"password\":\"$PW\",\"phone\":\"+1$RANDOM$RANDOM\",\"role\":\"$role\"}"
  local code; code=$(sql "select code from pending_registrations where email='$email'")
  body -X POST "$BASE/api/auth/register/verify" -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"code\":\"$code\"}" | json data.token
}
login() { body -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"$1\",\"password\":\"${2:-$PW}\"}"; }
token() { login "$1" "${2:-$PW}" | json data.token; }
uid() { sql "select user_id from users where email='$1'"; }

echo "== accounts (register -> verify -> approve -> login) =="
ADMIN="gate-admin-$STAMP@example.test"
sql "INSERT INTO users (email,password_hash,full_name,role,is_verified,is_active,is_locked)
     VALUES ('$ADMIN','\$2a\$10\$qULuA3kpH4oMKjMFTMSUHO6IrfuFIH6O8AhHm0DPkuzCp69Rb1i32','Gate Admin','ADMIN',true,true,false)" >/dev/null
A=$(token "$ADMIN" "Audit-Test-2026")
[ -n "$A" ] && pass "G2" "first admin inserted by SQL and logged in" || fail "G2" "admin login"

BENE="gate-bene-$STAMP@example.test"; B=$(register_and_verify "$BENE" beneficiary)
[ -n "$B" ] && pass "G2" "beneficiary: register -> verify -> token" || fail "G2" "beneficiary registration"
BENE2="gate-bene2-$STAMP@example.test"; B2=$(register_and_verify "$BENE2" beneficiary)

VOL="gate-vol-$STAMP@example.test"; VT=$(register_and_verify "$VOL" volunteer)
[ -z "$VT" ] && pass "G2" "volunteer registration is pending approval (no token)" || fail "G2" "volunteer got a token before approval"
check "G2" "volunteer login before approval" "$(code -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$VOL\",\"password\":\"$PW\"}")" "400"
check "G2" "admin approves volunteer" "$(code -X PUT "$BASE/api/admin/approve/$(uid "$VOL")" -H "Authorization: Bearer $A")" "200"
V=$(token "$VOL"); [ -n "$V" ] && pass "G2" "volunteer logs in after approval" || fail "G2" "volunteer login after approval"
VOL2="gate-vol2-$STAMP@example.test"; register_and_verify "$VOL2" volunteer >/dev/null
code -X PUT "$BASE/api/admin/approve/$(uid "$VOL2")" -H "Authorization: Bearer $A" >/dev/null; V2=$(token "$VOL2")
PSY="gate-psy-$STAMP@example.test"; register_and_verify "$PSY" psychologist >/dev/null
code -X PUT "$BASE/api/admin/approve/$(uid "$PSY")" -H "Authorization: Bearer $A" >/dev/null; S=$(token "$PSY")
[ -n "$S" ] && pass "G2" "psychologist approved and logged in" || fail "G2" "psychologist login"

echo "== Phase 1 =="
check "S-1" "register role=admin" "$(code -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' -d '{"fullName":"x","email":"evil@example.test","password":"hunter22","role":"admin"}')" "400"
for cat in "Anxiety" "Depression" "PTSD" "Grief & Loss" "Domestic Violence" "Crisis Support"; do
  out=$(body -X POST "$BASE/api/psychological-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" \
    -d "{\"supportType\":\"INDIVIDUAL\",\"category\":\"$cat\",\"description\":\"gate\"}")
  lab=$(echo "$out" | json data.category)
  case "$lab" in ANXIETY|DEPRESSION|PTSD|GRIEF|VIOLENCE|CRISIS) pass "D-1" "category '$cat' -> $lab";; *) fail "D-1" "category '$cat' -> '$lab'";; esac
done
check "UX-1" "public-stats without token" "$(code "$BASE/api/dashboard/public-stats")" "200"
check "S-6" "CORS evil origin gets no allow header" "$(curl -s -D - -o /dev/null -X OPTIONS "$BASE/api/dashboard/public-stats" -H 'Origin: https://evil.example' -H 'Access-Control-Request-Method: GET' | grep -ci 'access-control-allow-origin')" "0"
check "S-6" "CORS configured origin allowed (cross-host)" "$(curl -s -D - -o /dev/null -X OPTIONS "$BASE_ALT/api/dashboard/public-stats" -H 'Origin: http://localhost:8081' -H 'Access-Control-Request-Method: GET' | grep -ci 'access-control-allow-origin: http://localhost:8081')" "1"

echo "== Phase 2 =="
check "S-15" "no token" "$(code "$BASE/api/users/me")" "401"
check "S-15" "beneficiary on admin endpoint" "$(code "$BASE/api/admin/users" -H "Authorization: Bearer $B")" "403"
if [ -n "${JWT_SECRET:-}" ]; then
  EXP=$(python - "$JWT_SECRET" "$BENE" <<'PY'
import sys,hmac,hashlib,base64,json,time
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
h=b64(json.dumps({"alg":"HS256","typ":"JWT"},separators=(',',':')).encode())
now=int(time.time()); p=b64(json.dumps({"sub":sys.argv[2],"type":"access","iat":now-7200,"exp":now-3600},separators=(',',':')).encode())
sig=b64(hmac.new(sys.argv[1].encode(), f"{h}.{p}".encode(), hashlib.sha256).digest())
print(f"{h}.{p}.{sig}")
PY
)
  check "S-15" "expired token" "$(code "$BASE/api/users/me" -H "Authorization: Bearer $EXP")" "401"
else echo "  N-A   S-15   expired token (set JWT_SECRET to enable)"; fi

R_ID=$(body -X POST "$BASE/api/help-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" \
  -d '{"title":"Gate request A","helpType":"FOOD","urgencyLevel":"HIGH","peopleCount":2}' | json data.id)
check "S-4" "other beneficiary reads request" "$(code "$BASE/api/help-requests/$R_ID" -H "Authorization: Bearer $B2")" "404"
check "S-4" "owner reads request" "$(code "$BASE/api/help-requests/$R_ID" -H "Authorization: Bearer $B")" "200"
check "S-4" "admin reads request" "$(code "$BASE/api/help-requests/$R_ID" -H "Authorization: Bearer $A")" "200"
P_ID=$(body -X POST "$BASE/api/psychological-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" \
  -d '{"supportType":"INDIVIDUAL","category":"ANXIETY","description":"gate"}' | json data.id)
check "S-4" "other beneficiary reads psych request" "$(code "$BASE/api/psychological-requests/$P_ID" -H "Authorization: Bearer $B2")" "404"

check "P-1" "beneficiary lists help requests" "$(code "$BASE/api/help-requests" -H "Authorization: Bearer $B")" "403"
check "P-1" "psychologist lists help requests" "$(code "$BASE/api/help-requests" -H "Authorization: Bearer $S")" "403"
check "P-1" "volunteer lists help requests" "$(code "$BASE/api/help-requests" -H "Authorization: Bearer $V")" "200"
check "P-1" "volunteer on psychological endpoint" "$(code "$BASE/api/psychological-requests/my" -H "Authorization: Bearer $V")" "403"
check "P-1" "beneficiary on provider resources" "$(code "$BASE/api/provider-resources/me" -H "Authorization: Bearer $B")" "403"

# volunteer registers a resource and accepts request A; volunteer 2 also has a resource but is not assigned
check "G2" "volunteer registers FOOD resource" "$(code -X PUT "$BASE/api/provider-resources" -H 'Content-Type: application/json' -H "Authorization: Bearer $V" -d '{"helpType":"FOOD","capacityMode":"NUMERIC","capacityAmount":20}')" "200"
code -X PUT "$BASE/api/provider-resources" -H 'Content-Type: application/json' -H "Authorization: Bearer $V2" -d '{"helpType":"FOOD","capacityMode":"NUMERIC","capacityAmount":20}' >/dev/null
check "G2" "volunteer accepts request A" "$(code -X PUT "$BASE/api/help-requests/$R_ID/assign" -H "Authorization: Bearer $V")" "200"
check "S-5" "unassigned volunteer completes A" "$(code -X PUT "$BASE/api/help-requests/$R_ID/status?status=COMPLETED" -H "Authorization: Bearer $V2")" "403"
check "S-5" "beneficiary completes own A" "$(code -X PUT "$BASE/api/help-requests/$R_ID/status?status=COMPLETED" -H "Authorization: Bearer $B")" "403"
check "S-5" "assigned volunteer completes A" "$(code -X PUT "$BASE/api/help-requests/$R_ID/status?status=COMPLETED" -H "Authorization: Bearer $V")" "200"
check "G2" "smoke: A is COMPLETED with timestamp" "$(sql "select status||'/'||(completed_at is not null) from help_requests where request_id=$R_ID")" "COMPLETED/true"

# N-1: the beneficiary was told about the acceptance and the completion, in the caller's transaction;
# the acting volunteer was told nothing about their own actions; ownership is 404, never 403
NB=$(body "$BASE/api/notifications?size=5" -H "Authorization: Bearer $B")
check "N-1" "beneficiary has the two notifications for A, newest first" "$(echo "$NB" | python -c "import sys,json; c=json.load(sys.stdin)['data']['content']; print('/'.join(x['title'] for x in c if x['referenceId']==$R_ID))")" "Request completed/Your request was accepted"
check "N-1" "both unread, IN_APP, referencing the request" "$(echo "$NB" | python -c "import sys,json; c=[x for x in json.load(sys.stdin)['data']['content'] if x['referenceId']==$R_ID]; print(str(all(not x['read'] and x['type']=='IN_APP' and x['referenceType']=='HELP_REQUEST' for x in c)).lower())")" "true"
check "N-1" "unread count" "$(body "$BASE/api/notifications/unread-count" -H "Authorization: Bearer $B" | json data.unread)" "$(sql "select count(*) from notifications where user_id=$(uid "$BENE") and read_at is null")"
check "N-1" "the acting volunteer got nothing about A" "$(sql "select count(*) from notifications where user_id=$(uid "$VOL") and reference_id=$R_ID")" "0"
NID=$(echo "$NB" | python -c "import sys,json; c=json.load(sys.stdin)['data']['content']; print([x for x in c if x['referenceId']==$R_ID][0]['id'])")
check "N-1" "another user marks it read" "$(code -X PUT "$BASE/api/notifications/$NID/read" -H "Authorization: Bearer $B2")" "404"
check "N-1" "another user lists: not theirs" "$(body "$BASE/api/notifications" -H "Authorization: Bearer $B2" | python -c "import sys,json; print(sum(1 for x in json.load(sys.stdin)['data']['content'] if x['referenceId']==$R_ID))")" "0"
check "N-1" "owner marks it read" "$(body -X PUT "$BASE/api/notifications/$NID/read" -H "Authorization: Bearer $B" | json data.read)" "True"
check "N-1" "status column follows read_at" "$(sql "select cast(status as text)||'/'||(read_at is not null) from notifications where notification_id=$NID")" "READ/true"
check "N-1" "no token" "$(code "$BASE/api/notifications/unread-count")" "401"

# R-1: one completion report per assignment, by the assigned volunteer; one rating, by the beneficiary
ASG=$(body "$BASE/api/v1/assignments/help-requests/$R_ID" -H "Authorization: Bearer $V" | python -c "import sys,json; a=[x for x in json.load(sys.stdin)['data'] if x['status']=='COMPLETED']; print(a[-1]['assignmentId'])")
check "R-1" "no report yet" "$(code "$BASE/api/assignments/$ASG/report" -H "Authorization: Bearer $B")" "404"
check "R-1" "beneficiary files the provider's report" "$(code -X POST "$BASE/api/assignments/$ASG/report" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"description":"x"}')" "403"
check "R-1" "unassigned volunteer files a report" "$(code -X POST "$BASE/api/assignments/$ASG/report" -H 'Content-Type: application/json' -H "Authorization: Bearer $V2" -d '{"description":"x"}')" "404"
check "R-1" "beneficiary rates before the report exists" "$(code -X POST "$BASE/api/assignments/$ASG/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"rating":5}')" "400"
check "R-1" "assigned volunteer records the delivery" "$(body -X POST "$BASE/api/assignments/$ASG/report" -H 'Content-Type: application/json' -H "Authorization: Bearer $V" -d '{"description":"Food parcels for three people, delivered Tuesday"}' | json data.volunteerId)" "$(sql "select volunteer_id from volunteers where user_id=$(uid "$VOL")")"
check "R-1" "second report" "$(code -X POST "$BASE/api/assignments/$ASG/report" -H 'Content-Type: application/json' -H "Authorization: Bearer $V" -d '{"description":"again"}')" "409"
check "R-1" "exactly one row" "$(sql "select count(*) from reports where assignment_id=$ASG")" "1"
check "R-1" "beneficiary was asked to rate" "$(sql "select count(*) from notifications where user_id=$(uid "$BENE") and title like 'Delivery recorded%'")" "1"
check "R-1" "volunteer rates own delivery" "$(code -X POST "$BASE/api/assignments/$ASG/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $V" -d '{"rating":5}')" "403"
check "R-1" "rating out of range" "$(code -X POST "$BASE/api/assignments/$ASG/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"rating":6}')" "400"
check "R-1" "beneficiary rates once" "$(body -X POST "$BASE/api/assignments/$ASG/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"rating":4,"feedback":"Kind and on time"}' | json data.beneficiaryRating)" "4"
check "R-1" "second rating" "$(code -X POST "$BASE/api/assignments/$ASG/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"rating":1}')" "409"
check "R-1" "stored rating and feedback" "$(sql "select beneficiary_rating||'/'||feedback_from_beneficiary from reports where assignment_id=$ASG")" "4/Kind and on time"
check "R-1" "volunteer was told about the rating" "$(sql "select count(*) from notifications where user_id=$(uid "$VOL") and title='You received a rating: 4/5'")" "1"
check "R-1" "admin reads the report" "$(body "$BASE/api/assignments/$ASG/report" -H "Authorization: Bearer $A" | json data.beneficiaryRating)" "4"
check "R-1" "other beneficiary reads the report" "$(code "$BASE/api/assignments/$ASG/report" -H "Authorization: Bearer $B2")" "404"
check "R-1" "no photo upload endpoint" "$(code -X POST "$BASE/api/assignments/$ASG/report/photos" -H "Authorization: Bearer $V")" "404"

# AGG-1: the volunteer counters are recomputed from the reports on every report and rating
VOL_ROW="select total_completed_requests||'/'||coalesce(rating::text,'NULL') from volunteers where user_id=$(uid "$VOL")"
check "AGG-1" "after one report rated 4: count 1, mean 4.00" "$(sql "$VOL_ROW")" "1/4.00"
deliver_and_rate() { # rating -> the volunteer delivers one more FOOD request and the beneficiary rates it
  local rid asg
  rid=$(body -X POST "$BASE/api/help-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" -d "{\"title\":\"AGG request rated $1\",\"helpType\":\"FOOD\",\"urgencyLevel\":\"MEDIUM\",\"peopleCount\":1}" | json data.id)
  code -X PUT "$BASE/api/help-requests/$rid/assign" -H "Authorization: Bearer $V" >/dev/null
  code -X PUT "$BASE/api/help-requests/$rid/status?status=COMPLETED" -H "Authorization: Bearer $V" >/dev/null
  asg=$(body "$BASE/api/v1/assignments/help-requests/$rid" -H "Authorization: Bearer $V" | python -c "import sys,json; a=[x for x in json.load(sys.stdin)['data'] if x['status']=='COMPLETED']; print(a[-1]['assignmentId'])")
  code -X POST "$BASE/api/assignments/$asg/report" -H 'Content-Type: application/json' -H "Authorization: Bearer $V" -d '{"description":"delivered"}' >/dev/null
  code -X POST "$BASE/api/assignments/$asg/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d "{\"rating\":$1}" >/dev/null
}
deliver_and_rate 5
check "AGG-1" "after ratings 4, 5: count 2, mean 4.50" "$(sql "$VOL_ROW")" "2/4.50"
deliver_and_rate 3
check "AGG-1" "reports rated 4, 5, 3: rating = 4.00 and total_completed_requests = 3" "$(sql "$VOL_ROW")" "3/4.00"
check "AGG-1" "stored mean equals the mean of the reports" "$(sql "select (select rating from volunteers where user_id=$(uid "$VOL")) = (select round(avg(beneficiary_rating),2) from reports r join volunteers v on v.volunteer_id=r.volunteer_id where v.user_id=$(uid "$VOL"))")" "t"
check "AGG-1" "a volunteer with no reports has NULL, not 0" "$(sql "select total_completed_requests||'/'||coalesce(rating::text,'NULL') from volunteers where user_id=$(uid "$VOL2")")" "0/NULL"

# W-1: PENDING -> ASSIGNED -> IN_PROGRESS -> COMPLETED end to end; only the assigned provider or an admin may
# mark a request in progress; the beneficiary hears about it
W_ID=$(body -X POST "$BASE/api/help-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" -d '{"title":"Gate request W","helpType":"FOOD","urgencyLevel":"MEDIUM","peopleCount":1}' | json data.id)
check "W-1" "IN_PROGRESS straight from PENDING (admin)" "$(code -X PUT "$BASE/api/help-requests/$W_ID/status?status=IN_PROGRESS" -H "Authorization: Bearer $A")" "400"
check "W-1" "volunteer accepts W" "$(code -X PUT "$BASE/api/help-requests/$W_ID/assign" -H "Authorization: Bearer $V")" "200"
check "W-1" "beneficiary attempting IN_PROGRESS" "$(code -X PUT "$BASE/api/help-requests/$W_ID/status?status=IN_PROGRESS" -H "Authorization: Bearer $B")" "403"
check "W-1" "unassigned volunteer attempting IN_PROGRESS" "$(code -X PUT "$BASE/api/help-requests/$W_ID/status?status=IN_PROGRESS" -H "Authorization: Bearer $V2")" "403"
check "W-1" "assigned volunteer: on my way" "$(body -X PUT "$BASE/api/help-requests/$W_ID/status?status=IN_PROGRESS" -H "Authorization: Bearer $V" | json data.status)" "IN_PROGRESS"
check "W-1" "stored status; the assignment stays the provider's" "$(sql "select h.status||'/'||a.status from help_requests h join assignments a on a.request_id=h.request_id where h.request_id=$W_ID")" "IN_PROGRESS/ASSIGNED"
check "W-1" "the beneficiary was told" "$(sql "select count(*) from notifications where user_id=$(uid "$BENE") and reference_id=$W_ID and title='Request in progress'")" "1"
check "W-1" "repeating IN_PROGRESS" "$(code -X PUT "$BASE/api/help-requests/$W_ID/status?status=IN_PROGRESS" -H "Authorization: Bearer $V")" "409"
check "W-1" "beneficiary completes from IN_PROGRESS" "$(code -X PUT "$BASE/api/help-requests/$W_ID/status?status=COMPLETED" -H "Authorization: Bearer $B")" "403"
check "W-1" "assigned volunteer completes from IN_PROGRESS" "$(code -X PUT "$BASE/api/help-requests/$W_ID/status?status=COMPLETED" -H "Authorization: Bearer $V")" "200"
check "W-1" "COMPLETED with timestamp, assignment closed" "$(sql "select h.status||'/'||(h.completed_at is not null)||'/'||a.status from help_requests h join assignments a on a.request_id=h.request_id where h.request_id=$W_ID")" "COMPLETED/true/COMPLETED"
R_B=$(body -X POST "$BASE/api/help-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" -d '{"title":"Gate request B","helpType":"WATER","urgencyLevel":"LOW"}' | json data.id)
check "S-5" "beneficiary cancels own B" "$(code -X PUT "$BASE/api/help-requests/$R_B/status?status=CANCELLED" -H "Authorization: Bearer $B")" "200"
check "N-1" "cancelling an unassigned own request notifies nobody" "$(sql "select count(*) from notifications where reference_type='HELP_REQUEST' and reference_id=$R_B")" "0"
check "S-5" "other beneficiary cancels B" "$(code -X PUT "$BASE/api/help-requests/$R_B/status?status=CANCELLED" -H "Authorization: Bearer $B2")" "403"
check "S-5" "psychologist completes unassigned case" "$(code -X PUT "$BASE/api/psychological-requests/$P_ID/status?status=COMPLETED" -H "Authorization: Bearer $S")" "403"

check "S-10" "unknown route" "$(code "$BASE/api/does-not-exist" -H "Authorization: Bearer $V")" "404"
check "S-10" "non-numeric id" "$(code "$BASE/api/help-requests/abc" -H "Authorization: Bearer $V")" "400"
check "S-10" "500 body carries reference not detail" "$(body "$BASE/api/does-not-exist" -H "Authorization: Bearer $V" | grep -c 'No static resource')" "0"

u1=$(login "nobody-$STAMP@example.test" x | sed -E 's/"timestamp":"[^"]*",//'); u2=$(login "$BENE" wrong | sed -E 's/"timestamp":"[^"]*",//')
[ "$u1" = "$u2" ] && pass "S-8" "unknown email == wrong password body" || fail "S-8" "bodies differ: $u1 | $u2"
check "S-8" "login failure status" "$(code -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$BENE\",\"password\":\"wrong\"}")" "401"
f1=$(body -X POST "$BASE/api/auth/forgot-password" -H 'Content-Type: application/json' -d "{\"email\":\"$BENE2\"}"); f2=$(body -X POST "$BASE/api/auth/forgot-password" -H 'Content-Type: application/json' -d '{"email":"nobody@example.test"}')
[ "$f1" = "$f2" ] && pass "S-8" "forgot-password identical for known/unknown" || fail "S-8" "forgot-password bodies differ"

# S-7: reset password ends the session
RC=$(sql "select code from password_reset_tokens where email='$BENE2'")
check "S-9" "reset code was issued for the real account" "$([ -n "$RC" ] && echo yes)" "yes"
check "S-9" "wrong reset code is refused" "$(body -X POST "$BASE/api/auth/verify-reset-code" -H 'Content-Type: application/json' -d "{\"email\":\"$BENE2\",\"code\":\"WRONG1\"}" | json success)" "False"
check "S-7" "reset with the right code" "$(body -X POST "$BASE/api/auth/reset-password" -H 'Content-Type: application/json' -d "{\"email\":\"$BENE2\",\"code\":\"$RC\",\"newPassword\":\"New-Pass-2026!\"}" | json success)" "True"
sleep 1.1
check "S-7" "old access token after reset" "$(code "$BASE/api/users/me" -H "Authorization: Bearer $B2")" "401"
B2=$(token "$BENE2" 'New-Pass-2026!'); [ -n "$B2" ] && pass "S-7" "login with new password" || fail "S-7" "login with new password"


# ON-1
ON=$(body -X POST "$BASE/api/help-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $V" \
  -d "{\"title\":\"Filed for someone\",\"helpType\":\"FOOD\",\"urgencyLevel\":\"HIGH\",\"beneficiaryName\":\"Nadia Gate\",\"beneficiaryEmail\":\"nadia-$STAMP@example.test\",\"beneficiaryPhone\":\"+199$RANDOM\"}")
ON_ID=$(echo "$ON" | json data.id); ON_BEN=$(echo "$ON" | json data.beneficiaryId); ON_FILER=$(echo "$ON" | json data.filedByUserId)
[ -n "$ON_ID" ] && [ "$ON_BEN" != "$ON_FILER" ] && pass "ON-1" "volunteer files for new beneficiary (beneficiary $ON_BEN, filer $ON_FILER)" || fail "ON-1" "on-behalf creation: $ON"
check "ON-1" "created beneficiary is active, unverified" "$(sql "select role||'/'||is_active||'/'||is_verified from users where user_id=${ON_BEN:-0}")" "BENEFICIARY/true/false"
check "ON-1" "filer cannot accept own filing" "$(code -X PUT "$BASE/api/help-requests/$ON_ID/assign" -H "Authorization: Bearer $V")" "400"
check "ON-1" "filer can read it" "$(code "$BASE/api/help-requests/$ON_ID" -H "Authorization: Bearer $V")" "200"
check "ON-1" "filer cannot complete it" "$(code -X PUT "$BASE/api/help-requests/$ON_ID/status?status=COMPLETED" -H "Authorization: Bearer $V")" "403"
check "ON-1" "beneficiary supplying beneficiaryEmail" "$(code -X POST "$BASE/api/help-requests" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"title":"x","helpType":"FOOD","urgencyLevel":"LOW","beneficiaryEmail":"a@b.co"}')" "400"
# ON-2: the "Filed on behalf of <name>" badge gets the name only where the viewer could already learn it
check "ON-2" "the filer's response carries the beneficiary's name" "$(echo "$ON" | json data.beneficiaryName)" "Nadia Gate"
check "ON-2" "the filer sees the name in the list" "$(body "$BASE/api/help-requests?page=0&size=100" -H "Authorization: Bearer $V" | python -c "import sys,json; r=[x for x in json.load(sys.stdin)['data']['content'] if x['id']==$ON_ID][0]; print(str(r['filedByUserId'])+'/'+str(r.get('beneficiaryName')))")" "$ON_FILER/Nadia Gate"
check "ON-2" "another provider sees that it was filed, not for whom" "$(body "$BASE/api/help-requests?page=0&size=100" -H "Authorization: Bearer $V2" | python -c "import sys,json; r=[x for x in json.load(sys.stdin)['data']['content'] if x['id']==$ON_ID][0]; print(str(r['filedByUserId'])+'/'+str('beneficiaryName' in r))")" "$ON_FILER/False"
check "ON-2" "the admin sees the name" "$(body "$BASE/api/help-requests/$ON_ID" -H "Authorization: Bearer $A" | json data.beneficiaryName)" "Nadia Gate"
check "ON-2" "a self-filed request carries no name field" "$(body "$BASE/api/help-requests/$R_ID" -H "Authorization: Bearer $B" | grep -c beneficiaryName)" "0"

echo "== Phase 3 =="
# L-1: volunteer 2 sets a location and has a FOOD resource; a request with coordinates nearby is auto-assigned
check "L-1" "volunteer saves matching location" "$(code -X PUT "$BASE/api/users/me/profile" -H 'Content-Type: application/json' -H "Authorization: Bearer $V2" -d '{"address":"Tripoli","latitude":32.8872,"longitude":13.1913}')" "200"
L1N=$(body -X POST "$BASE/api/help-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" -d '{"title":"No coords","helpType":"FOOD","urgencyLevel":"HIGH","peopleCount":2}' | json data.status)
check "L-1" "request without coordinates stays PENDING" "$L1N" "PENDING"
L1=$(body -X POST "$BASE/api/help-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" -d '{"title":"With coords","helpType":"FOOD","urgencyLevel":"HIGH","peopleCount":2,"latitude":32.8925,"longitude":13.1802}')
L1_ID=$(echo "$L1" | json data.id)
check "L-1" "request with coordinates is ASSIGNED" "$(echo "$L1" | json data.status)" "ASSIGNED"
check "L-1" "assignment_source = AUTO_GEO" "$(sql "select assignment_source from assignments where request_id=${L1_ID:-0}")" "AUTO_GEO"

check "F-2" "no page defines its own API/authHeader/escHtml/logout/buildSidebar" "$(grep -lE "const API\s*=|function authHeader|function escHtml|function logout\b|function buildSidebar" src/main/resources/static/*.html | wc -l)" "0"
# since F-5 the page scripts live in js/<page>.js; a page whose script calls the API must load the shared module once
check "F-2" "every API page includes the shared module once" "$(for f in src/main/resources/static/*.html; do js="src/main/resources/static/js/$(basename "${f%.html}" | tr 'A-Z' 'a-z').js"; [ -f "$js" ] && grep -q 'apiFetch\|fetch(' "$js" && grep -c 'nidaa-common.js' "$f"; done | sort -u | tr -d '\n')" "1"

check "S-2" "img payload as title" "$(code -X POST "$BASE/api/help-requests" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"title":"<img src=x onerror=alert(1)>","helpType":"FOOD","urgencyLevel":"HIGH"}')" "400"
check "S-2" "CSP with connect-src self on a page" "$(curl -s -D - -o /dev/null "$BASE/admin-requests.html" | grep -i 'content-security-policy' | grep -c "connect-src 'self'")" "1"
check "S-2" "overlong description" "$(code -X POST "$BASE/api/help-requests" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d "{\"title\":\"t\",\"helpType\":\"FOOD\",\"urgencyLevel\":\"HIGH\",\"description\":\"$(printf 'd%.0s' $(seq 1 4001))\"}")" "400"

LG=$(login "$BENE"); RT=$(echo "$LG" | json data.refreshToken); AT=$(echo "$LG" | json data.token)
NEW=$(body -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}")
[ -n "$(echo "$NEW" | json data.token)" ] && [ "$(echo "$NEW" | json data.refreshToken)" != "$RT" ] && pass "F-4" "refresh returns a rotated pair" || fail "F-4" "refresh: $NEW"
check "F-4" "old refresh token reused" "$(code -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}")" "400"
RT2=$(echo "$NEW" | json data.refreshToken)
check "F-4" "logout revokes refresh token" "$(code -X POST "$BASE/api/auth/logout" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT2\"}")" "200"
check "F-4" "revoked refresh token" "$(code -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT2\"}")" "400"

check "B-2" "unknown helpType" "$(body -X POST "$BASE/api/help-requests" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"title":"x","helpType":"GROCERIES","urgencyLevel":"HIGH"}' | json details.helpType | grep -c 'must be one of')" "1"
check "B-2" "no request stored as OTHER" "$(sql "select count(*) from help_requests where help_type='OTHER'")" "0"

# VER: approval and credential verification are two administrator actions (decision after
# Gate 4); crisis routing needs is_verified AND the psychologist's own duty toggle (UX-2)
PSY_UID=$(uid "$PSY")
check "VER" "approved psychologist is off duty and unverified" "$(sql "select is_on_duty||'/'||is_verified from psychologists where user_id=$PSY_UID")" "false/false"
check "VER" "psychologist cannot verify own credentials" "$(code -X PUT "$BASE/api/admin/psychologists/$PSY_UID/verification" -H 'Content-Type: application/json' -H "Authorization: Bearer $S" -d '{"verified":true}')" "403"
check "VER" "verifying a volunteer's credentials" "$(code -X PUT "$BASE/api/admin/psychologists/$(uid "$VOL")/verification" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d '{"verified":true}')" "400"
check "VER" "body without the flag" "$(code -X PUT "$BASE/api/admin/psychologists/$PSY_UID/verification" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d '{}')" "400"
check "VER" "admin verifies credentials" "$(body -X PUT "$BASE/api/admin/psychologists/$PSY_UID/verification" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d '{"verified":true}' | json data.verified)" "True"
check "VER" "verified_by is the admin, action audited once" "$(sql "select (verified_by=$(uid "$ADMIN"))||'/'||(verified_at is not null)||'/'||(select count(*) from activity_logs where action='PSYCHOLOGIST_VERIFIED' and entity_id=$PSY_UID) from psychologists where user_id=$PSY_UID")" "true/true/1"
check "VER" "repeating the same state is a no-op" "$(body -X PUT "$BASE/api/admin/psychologists/$PSY_UID/verification" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d '{"verified":true}' | json data.verified)/$(sql "select count(*) from activity_logs where action='PSYCHOLOGIST_VERIFIED' and entity_id=$PSY_UID")" "True/1"
check "VER" "verified but off duty: not in the routing pool" "$(sql "select count(*) from psychologists where is_verified and is_on_duty")" "0"
check "VER" "psychologist goes on duty (UX-2)" "$(code -X PUT "$BASE/api/psychologists/me/duty" -H 'Content-Type: application/json' -H "Authorization: Bearer $S" -d '{"onDuty":true}')" "200"
check "N-1" "approval notified the volunteer, not the admin" "$(sql "select count(*) filter (where user_id=$(uid "$VOL"))||'/'||count(*) filter (where user_id=$(uid "$ADMIN")) from notifications where title='Your application was approved'")" "1/0"
check "VER" "users list shows the two flags to the admin" "$(body "$BASE/api/admin/users" -H "Authorization: Bearer $A" | python -c "import sys,json; u=[x for x in json.load(sys.stdin)['data'] if x['id']==$PSY_UID][0]; print(str(u.get('credentialsVerified')).lower()+'/'+str(u.get('onDuty')).lower())")" "true/true"

crisis() { body -X POST "$BASE/api/psychological-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" \
  -d "{\"supportType\":\"INDIVIDUAL\",\"category\":\"ANXIETY\",\"description\":\"$1\"}" | python -c "import sys,json; d=json.load(sys.stdin)['data']; print(str(d['isCrisis']).lower()+'/'+str(d['needsReview']).lower())"; }
check "L-2" "'urgent' alone" "$(crisis 'This is urgent, please help me today')" "false/false"
check "L-2" "'kill myself'" "$(crisis 'Some nights I want to kill myself')" "true/false"
check "L-2" "'self-harming' (inflected)" "$(crisis 'I have been self-harming again')" "true/false"
check "L-2" "'hopeless' -> review" "$(crisis 'Everything feels hopeless')" "false/true"
check "L-2" "crisis case routed to on-duty psychologist" "$(sql "select count(*) from assignments where assignment_source='AUTO_CRISIS'")" "$(sql "select count(*) from psychological_requests where is_crisis and status='ASSIGNED'")"
# with VER above the pool is no longer empty, so this must have happened at least twice (two crisis texts)
check "N-1" "each routed crisis case notified the psychologist and the person" "$(sql "select count(*) filter (where user_id=$PSY_UID and title='Crisis case routed to you')||'/'||count(*) filter (where user_id=$(uid "$BENE") and title='A psychologist has been assigned to you') from notifications where reference_type='PSYCHOLOGICAL_REQUEST'")" "2/2"
check "L-2" "the two crisis cases were actually auto-routed" "$(sql "select count(*) from assignments where assignment_source='AUTO_CRISIS' and psychologist_id=(select psychologist_id from psychologists where user_id=$PSY_UID)")" "2"

# CS-1: one consultation record per completed case, by the assigned psychologist; one rating, by the
# beneficiary; the psychologist's private note reaches nobody else; an anonymous case stays anonymous
C_ID=$(body -X POST "$BASE/api/psychological-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" \
  -d '{"supportType":"INDIVIDUAL","category":"GRIEF","preferredFormat":"VIDEO","description":"gate consultation","isAnonymous":true}' | json data.id)
check "CS-1" "psychologist accepts the anonymous case" "$(code -X PUT "$BASE/api/psychological-requests/$C_ID/accept" -H "Authorization: Bearer $S")" "200"
CONSULT='{"format":"video","durationMinutes":45,"topicsDiscussed":["sleep","grief"],"recommendations":"Keep a sleep diary","notesForPsychologist":"PRIVATE-NOTE consider referral"}'
check "CS-1" "record before completion" "$(code -X POST "$BASE/api/psychological-requests/$C_ID/consultation" -H 'Content-Type: application/json' -H "Authorization: Bearer $S" -d "$CONSULT")" "400"
check "CS-1" "assigned psychologist completes the case" "$(code -X PUT "$BASE/api/psychological-requests/$C_ID/status?status=COMPLETED" -H "Authorization: Bearer $S")" "200"
check "CS-1" "completed_at is written on completion" "$(sql "select status||'/'||(completed_at is not null) from psychological_requests where request_id=$C_ID")" "COMPLETED/true"
check "CS-1" "no consultation yet" "$(code "$BASE/api/psychological-requests/$C_ID/consultation" -H "Authorization: Bearer $B")" "404"
check "CS-1" "beneficiary records the psychologist's consultation" "$(code -X POST "$BASE/api/psychological-requests/$C_ID/consultation" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d "$CONSULT")" "403"
check "CS-1" "beneficiary rates before the record exists" "$(code -X POST "$BASE/api/psychological-requests/$C_ID/consultation/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"rating":5}')" "400"
check "CS-1" "unknown format" "$(code -X POST "$BASE/api/psychological-requests/$C_ID/consultation" -H 'Content-Type: application/json' -H "Authorization: Bearer $S" -d '{"format":"PHONE"}')" "400"
CREC=$(body -X POST "$BASE/api/psychological-requests/$C_ID/consultation" -H 'Content-Type: application/json' -H "Authorization: Bearer $S" -d "$CONSULT")
check "CS-1" "assigned psychologist records the consultation" "$(echo "$CREC" | json data.format)/$(echo "$CREC" | json data.durationMinutes)/$(echo "$CREC" | json data.topicsDiscussed.1)" "VIDEO/45/grief"
check "CS-1" "the record links the assignment that closed the case" "$(echo "$CREC" | json data.assignmentId)" "$(sql "select assignment_id from assignments where psychological_request_id=$C_ID and status='COMPLETED'")"
check "CS-1" "the psychologist sees their own private note" "$(echo "$CREC" | json data.notesForPsychologist)" "PRIVATE-NOTE consider referral"
check "CS-1" "the response carries no beneficiary identity" "$(echo "$CREC" | grep -c "beneficiaryId\|beneficiaryName\|Gate beneficiary\|$BENE")" "0"
check "CS-1" "second record" "$(code -X POST "$BASE/api/psychological-requests/$C_ID/consultation" -H 'Content-Type: application/json' -H "Authorization: Bearer $S" -d "$CONSULT")" "409"
check "CS-1" "exactly one row, format stored in the enum column, topics as text[]" "$(sql "select count(*)||'/'||max(cast(format as text))||'/'||max(array_length(topics_discussed,1)) from consultations where psychological_request_id=$C_ID")" "1/VIDEO/2"
check "CS-1" "beneficiary was asked to rate" "$(sql "select count(*) from notifications where user_id=$(uid "$BENE") and title='Your consultation was recorded: please rate it'")" "1"
PSY_ROW="select consultation_count||'/'||coalesce(rating::text,'NULL') from psychologists where user_id=$PSY_UID"
check "AGG-1" "after the record, before any rating: count 1, rating NULL" "$(sql "$PSY_ROW")" "1/NULL"
BVIEW=$(body "$BASE/api/psychological-requests/$C_ID/consultation" -H "Authorization: Bearer $B")
check "CS-1" "beneficiary reads the recommendations" "$(echo "$BVIEW" | json data.recommendations)" "Keep a sleep diary"
check "CS-1" "notes_for_psychologist is never returned to the beneficiary (key absent)" "$(echo "$BVIEW" | grep -c 'notesForPsychologist\|PRIVATE-NOTE')" "0"
check "CS-1" "nor to the administrator" "$(body "$BASE/api/psychological-requests/$C_ID/consultation" -H "Authorization: Bearer $A" | grep -c 'notesForPsychologist\|PRIVATE-NOTE')" "0"
check "CS-1" "other beneficiary reads the consultation" "$(code "$BASE/api/psychological-requests/$C_ID/consultation" -H "Authorization: Bearer $B2")" "404"
check "CS-1" "volunteer on the consultation" "$(code "$BASE/api/psychological-requests/$C_ID/consultation" -H "Authorization: Bearer $V")" "403"
check "CS-1" "psychologist rates own consultation" "$(code -X POST "$BASE/api/psychological-requests/$C_ID/consultation/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $S" -d '{"rating":5}')" "403"
check "CS-1" "rating out of range" "$(code -X POST "$BASE/api/psychological-requests/$C_ID/consultation/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"rating":0}')" "400"
FVIEW=$(body -X POST "$BASE/api/psychological-requests/$C_ID/consultation/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"rating":4,"feedback":"Felt heard"}')
check "CS-1" "beneficiary rates once" "$(echo "$FVIEW" | json data.rating)/$(echo "$FVIEW" | json data.feedbackFromBeneficiary)" "4/Felt heard"
check "CS-1" "second rating" "$(code -X POST "$BASE/api/psychological-requests/$C_ID/consultation/feedback" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d '{"rating":1}')" "409"
check "CS-1" "stored rating and feedback" "$(sql "select rating||'/'||feedback_from_beneficiary from consultations where psychological_request_id=$C_ID")" "4/Felt heard"
check "AGG-1" "after the rating: consultation_count 1, rating 4.00" "$(sql "$PSY_ROW")" "1/4.00"
check "CS-1" "the psychologist was told the rating, not who gave it" "$(sql "select count(*) from notifications where user_id=$PSY_UID and title='You received a rating: 4/5' and content not like '%Gate beneficiary%' and content not like '%$BENE%'")" "1"
check "CS-1" "anonymous contact is still anonymous to the psychologist" "$(body "$BASE/api/psychological-requests/$C_ID/contact" -H "Authorization: Bearer $S" | json data.anonymous)" "True"

# CM-1: likes and comments live on the server; same role gate as the feed, same cap, same moderation audit
M_ID=$(body -X POST "$BASE/api/community/messages" -H 'Content-Type: application/json' -H "Authorization: Bearer $V" -d '{"content":"Gate post for likes and comments","communityCategory":"UPDATE"}' | json data.id)
check "CM-1" "beneficiary is outside the community (like)" "$(code -X POST "$BASE/api/community/messages/$M_ID/like" -H "Authorization: Bearer $B")" "403"
check "CM-1" "volunteer likes" "$(body -X POST "$BASE/api/community/messages/$M_ID/like" -H "Authorization: Bearer $V" | python -c "import sys,json; d=json.load(sys.stdin)['data']; print(str(d['likedByMe']).lower()+'/'+str(d['likeCount']))")" "true/1"
check "CM-1" "liking twice leaves one like" "$(body -X POST "$BASE/api/community/messages/$M_ID/like" -H "Authorization: Bearer $V" | json data.likeCount)" "1"
check "CM-1" "second volunteer likes" "$(body -X POST "$BASE/api/community/messages/$M_ID/like" -H "Authorization: Bearer $V2" | json data.likeCount)" "2"
check "CM-1" "exactly one row per (post, user)" "$(sql "select count(*) from message_reactions where message_id=$M_ID")" "2"
check "CM-1" "the feed carries the count and the viewer's own like" "$(body "$BASE/api/community/messages?page=0&size=50" -H "Authorization: Bearer $V2" | python -c "import sys,json; m=[x for x in json.load(sys.stdin)['data']['content'] if x['id']==$M_ID][0]; print(str(m['likeCount'])+'/'+str(m['likedByMe']).lower()+'/'+str(m['commentCount']))")" "2/true/0"
check "CM-1" "the psychologist sees the same count without their own like" "$(body "$BASE/api/community/messages?page=0&size=50" -H "Authorization: Bearer $S" | python -c "import sys,json; m=[x for x in json.load(sys.stdin)['data']['content'] if x['id']==$M_ID][0]; print(str(m['likeCount'])+'/'+str(m['likedByMe']).lower())")" "2/false"
check "CM-1" "volunteer unlikes" "$(body -X DELETE "$BASE/api/community/messages/$M_ID/like" -H "Authorization: Bearer $V" | python -c "import sys,json; d=json.load(sys.stdin)['data']; print(str(d['likedByMe']).lower()+'/'+str(d['likeCount']))")" "false/1"
check "CM-1" "like on a post that does not exist" "$(code -X POST "$BASE/api/community/messages/999999/like" -H "Authorization: Bearer $V")" "404"
check "CM-1" "blank comment" "$(code -X POST "$BASE/api/community/messages/$M_ID/comments" -H 'Content-Type: application/json' -H "Authorization: Bearer $V2" -d '{"content":"   "}')" "400"
check "CM-1" "overlong comment (1001 chars)" "$(code -X POST "$BASE/api/community/messages/$M_ID/comments" -H 'Content-Type: application/json' -H "Authorization: Bearer $V2" -d "{\"content\":\"$(printf 'c%.0s' $(seq 1 1001))\"}")" "400"
CMT=$(body -X POST "$BASE/api/community/messages/$M_ID/comments" -H 'Content-Type: application/json' -H "Authorization: Bearer $V2" -d '{"content":"Thank you for sharing this"}')
CMT_ID=$(echo "$CMT" | json data.id)
check "CM-1" "second volunteer comments" "$(echo "$CMT" | json data.authorName)/$(echo "$CMT" | json data.content)" "Gate volunteer/Thank you for sharing this"
check "CM-1" "the thread lists it for the psychologist" "$(body "$BASE/api/community/messages/$M_ID/comments" -H "Authorization: Bearer $S" | python -c "import sys,json; d=json.load(sys.stdin)['data']; print(str(d['totalElements'])+'/'+d['content'][0]['authorName'])")" "1/Gate volunteer"
check "CM-1" "the feed's comment count follows" "$(body "$BASE/api/community/messages?page=0&size=50" -H "Authorization: Bearer $V" | python -c "import sys,json; m=[x for x in json.load(sys.stdin)['data']['content'] if x['id']==$M_ID][0]; print(m['commentCount'])")" "1"
check "CM-1" "a volunteer cannot remove a comment" "$(code -X DELETE "$BASE/api/community/messages/$M_ID/comments/$CMT_ID?reason=x" -H "Authorization: Bearer $V")" "403"
check "CM-1" "admin removal needs a reason" "$(code -X DELETE "$BASE/api/community/messages/$M_ID/comments/$CMT_ID" -H "Authorization: Bearer $A")" "400"
check "CM-1" "admin removes the comment with a reason" "$(body -X DELETE "$BASE/api/community/messages/$M_ID/comments/$CMT_ID?reason=Off-topic" -H "Authorization: Bearer $A" | python -c "import sys,json; d=json.load(sys.stdin)['data']; print(str(d['messageId'])+'/'+str(d['commentId'])+'/'+d['reason'])")" "$M_ID/$CMT_ID/Off-topic"
check "CM-1" "soft-deleted, audited with the comment id" "$(sql "select (select is_deleted from message_comments where id=$CMT_ID)||'/'||(select count(*) from message_deletions where comment_id=$CMT_ID and message_id=$M_ID and original_content='Thank you for sharing this')")" "true/1"
check "CM-1" "the author was told the reason" "$(sql "select count(*) from notifications where user_id=$(uid "$VOL2") and title='A moderator removed your comment' and content like '%Off-topic%'")" "1"
check "CM-1" "the thread and the count no longer show it" "$(body "$BASE/api/community/messages/$M_ID/comments" -H "Authorization: Bearer $V" | json data.totalElements)/$(body "$BASE/api/community/messages?page=0&size=50" -H "Authorization: Bearer $V" | python -c "import sys,json; m=[x for x in json.load(sys.stdin)['data']['content'] if x['id']==$M_ID][0]; print(m['commentCount'])")" "0/0"
check "CM-1" "the moderation history lists it as a comment" "$(body "$BASE/api/admin/community/deletions?page=0&size=5" -H "Authorization: Bearer $A" | python -c "import sys,json; d=[x for x in json.load(sys.stdin)['data']['content'] if x.get('commentId')==$CMT_ID]; print(len(d))")" "1"
check "CM-1" "no localStorage engagement code remains" "$(grep -c "nidaa_community_engagement\|readEngagementStore\|saveEngagement\|removeEngagement" src/main/resources/static/js/community.js)" "0"

check "D-5" "no priority_score above 100" "$(sql "select count(*) from help_requests where priority_score > 100")" "0"

# D-2
B2_UID=$(uid "$BENE2")
check "D-2" "self-delete without password" "$(code -X DELETE "$BASE/api/users/me" -H 'Content-Type: application/json' -H "Authorization: Bearer $B2" -d '{}')" "400"
check "D-2" "self-delete with password" "$(code -X DELETE "$BASE/api/users/me" -H 'Content-Type: application/json' -H "Authorization: Bearer $B2" -d '{"password":"New-Pass-2026!"}')" "200"
check "D-2" "row anonymised" "$(sql "select (email like 'deleted-%@deleted.invalid')||'/'||full_name||'/'||coalesce(phone,'NULL')||'/'||is_active from users where user_id=$B2_UID")" "true/Deleted user/NULL/false"
check "D-2" "old email login" "$(code -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$BENE2\",\"password\":\"New-Pass-2026!\"}")" "401"

echo "== S-9 (last: the burst exhausts one address's bucket) =="
ALT_IP=$(echo "$BASE_ALT" | sed -E 's#https?://([^:/]+).*#\1#')
# 80 calls: the bucket holds 50 and refills greedily at 50/minute, so a burst
# that takes a few seconds earns a handful back before it is exhausted.
echo "  ..    S-9    80 rapid auth calls from source $ALT_IP (limit set to 50 for this run)"
# Retry-After is read off the burst's own first 429: a separate probe after the burst can land on a
# token the bucket earned back in the meantime and answer 400/401 with no header (seen once).
last=""; first429=""; retry_after=""; for i in $(seq 1 80); do h=$(curl -s --interface "$ALT_IP" -D - -o /dev/null -X POST "$BASE_ALT/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"burst@example.test","password":"x"}'); last=$(echo "$h" | head -1 | awk '{print $2}'); [ -z "$first429" ] && [ "$last" = "429" ] && { first429=$i; retry_after=$(echo "$h" | grep -ci 'retry-after'); }; done
# a token bucket keeps refilling during the burst, so later calls may pass again;
# the criterion is that the limiter engaged once the 50-token budget was spent
check "S-9" "limiter engaged during the burst (first 429 at call #${first429:-none}, last=$last)" "$([ -n "$first429" ] && echo engaged)" "engaged"
check "S-9" "Retry-After present on the first 429" "${retry_after:-0}" "1"
check "S-9" "primary address still served" "$(code "$BASE/api/dashboard/public-stats")" "200"

echo "== T-2 =="
check "T-2" "persistence tests ran, none skipped (last surefire run)" "$(grep -h 'skipped=' target/surefire-reports/TEST-com.humanitarian.platform.persistence.*.xml 2>/dev/null | grep -o 'skipped="[0-9]*"' | sort -u | tr -d '\n')" 'skipped="0"'

echo
echo "== invariants on $DB =="
while IFS='|' read -r name n; do
  [ -z "$name" ] && continue
  if [ "$n" = "0" ]; then pass "INV" "$name = 0"; else fail "INV" "$name = $n"; fi
done < <("$PSQL" -U "$PGUSER" -d "$DB" -tAc "$(sed 's/--.*//' scripts/gate/invariants.sql)" | tr -d '\r')

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
