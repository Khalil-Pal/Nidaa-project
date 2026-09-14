# Gate scripts

Reusable checks for the phase gates (Master Plan §2). Run them at every phase
boundary and record the output in `docs/gates/`.

| Script | Gate items | What it does |
|---|---|---|
| `fresh-db.sh [db]` | G2, G3 | Builds a database from `database/migrations/V*.sql` in version order, re-runs V9+ to prove idempotency (exit codes, not stderr), prints the trigger/function/view inventory |
| `invariants.sql` | G3 | The invariant query set from the Master Plan appendix; every count must be 0 |
| `smtp-sink.py [port]` | G2 | Accepts every email and prints sender, recipients and subject; registration is transactional with the verification email, so the smoke path needs a mail server |
| `acceptance.sh` | G4, G5 | Re-verifies every task's acceptance criterion through the API against the fresh database: registration, approval, login, ownership, transitions, rate limiting, on-behalf filing, geolocation matching, validation, crisis scoring, soft delete, and the invariants |
| `browser-checks.js` | G5 | What the pages actually do in a real browser: XSS payload rendered as text, geolocation granted and denied, silent token refresh |

## Running Gate N

```bash
# 1. fresh database from the migrations (also prints the G3 inventory)
PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe" scripts/gate/fresh-db.sh nidaa_gate

# 2. a mail sink, in its own terminal
python scripts/gate/smtp-sink.py 1025

# 3. the application against that database, mail to the sink, a wider auth budget
DB_URL="jdbc:postgresql://localhost:5432/nidaa_gate?stringtype=unspecified" \
RATELIMIT_AUTH_PER_MINUTE=50 MAIL_HOST=127.0.0.1 MAIL_PORT=1025 MAIL_SSL=false MAIL_AUTH=false \
./mvnw spring-boot:run

# 4. API acceptance (BASE_ALT must be a second loopback address; the S-9 burst binds to it)
BASE=http://127.0.0.1:8081 BASE_ALT=http://127.0.0.2:8081 DB=nidaa_gate \
PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe" JWT_SECRET=<the app's secret> \
bash scripts/gate/acceptance.sh

# 5. browser checks (once: npm install --no-save puppeteer-core in scripts/gate, or set NODE_PATH)
#    plant the XSS-title row first, since the API refuses it by design:
#    INSERT INTO help_requests (beneficiary_id,title,description,help_type,urgency_level,status,priority_score)
#      VALUES (<beneficiary id>, '<img src=x onerror=alert(1)>', 'planted', 'FOOD', 'HIGH', 'PENDING', 40);
BASE=http://127.0.0.1:8081 ADMIN_EMAIL=... ADMIN_PASSWORD=... BENE_EMAIL=... BENE_PASSWORD=... \
VOL_EMAIL=... VOL_PASSWORD=... JWT_SECRET=... CHROME="C:/Program Files/Google/Chrome/Application/chrome.exe" \
node scripts/gate/browser-checks.js

# 5b. Lighthouse (F-3 / PF-1): once `npm install --no-save lighthouse puppeteer-core`, then
#     node scripts/gate/lighthouse.js http://127.0.0.1:8081/index.html after
#     -> prints and writes lighthouse-after.json (performance, LCP, image bytes; mobile + desktop)

# 6. fail-fast check: move .env aside, unset JWT_SECRET, start on another port, expect
#    "Could not resolve placeholder 'JWT_SECRET'"; then restore .env

# 7. drop the gate database
psql -U postgres -d postgres -c "DROP DATABASE nidaa_gate;"
```

Notes learned the hard way, so the scripts already handle them: `psql` on Windows
emits CRLF (stripped); a connection to `127.0.0.2` is sourced from `127.0.0.1` unless
curl binds with `--interface`; bucket4j refills during a burst, so the criterion is
"the limiter engaged", not "every later call is 429"; PostgreSQL renders booleans as
`true`/`false` in string concatenation.
