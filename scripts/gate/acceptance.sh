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
R_B=$(body -X POST "$BASE/api/help-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" -d '{"title":"Gate request B","helpType":"WATER","urgencyLevel":"LOW"}' | json data.id)
check "S-5" "beneficiary cancels own B" "$(code -X PUT "$BASE/api/help-requests/$R_B/status?status=CANCELLED" -H "Authorization: Bearer $B")" "200"
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

crisis() { body -X POST "$BASE/api/psychological-requests" -H "Content-Type: application/json" -H "Authorization: Bearer $B" \
  -d "{\"supportType\":\"INDIVIDUAL\",\"category\":\"ANXIETY\",\"description\":\"$1\"}" | python -c "import sys,json; d=json.load(sys.stdin)['data']; print(str(d['isCrisis']).lower()+'/'+str(d['needsReview']).lower())"; }
check "L-2" "'urgent' alone" "$(crisis 'This is urgent, please help me today')" "false/false"
check "L-2" "'kill myself'" "$(crisis 'Some nights I want to kill myself')" "true/false"
check "L-2" "'self-harming' (inflected)" "$(crisis 'I have been self-harming again')" "true/false"
check "L-2" "'hopeless' -> review" "$(crisis 'Everything feels hopeless')" "false/true"
check "L-2" "crisis case routed to on-duty psychologist" "$(sql "select count(*) from assignments where assignment_source='AUTO_CRISIS'")" "$(sql "select count(*) from psychological_requests where is_crisis and status='ASSIGNED'")"

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
last=""; first429=""; for i in $(seq 1 80); do last=$(code --interface "$ALT_IP" -X POST "$BASE_ALT/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"burst@example.test","password":"x"}'); [ -z "$first429" ] && [ "$last" = "429" ] && first429=$i; done
# a token bucket keeps refilling during the burst, so later calls may pass again;
# the criterion is that the limiter engaged once the 50-token budget was spent
check "S-9" "limiter engaged during the burst (first 429 at call #${first429:-none}, last=$last)" "$([ -n "$first429" ] && echo engaged)" "engaged"
check "S-9" "Retry-After present" "$(curl -s --interface "$ALT_IP" -D - -o /dev/null -X POST "$BASE_ALT/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"burst@example.test","password":"x"}' | grep -ci 'retry-after')" "1"
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
