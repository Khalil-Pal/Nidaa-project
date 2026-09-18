# Session handoff — Nidaa audit remediation

Written 2026-09-16 at HEAD `dc4d243` on `audit-remediation`, updated 2026-09-18 after Gate 6
(§2 counts, §4 and §6 rewritten; §1, §3 and §5 checked again and unchanged), for the next Claude
Code session. Every statement here was checked on this machine on that date (commands run,
files read), not recalled.

This file holds only what a new session could **not** learn from the repository. It does
not restate:

- the plan, phases, gates, standing rules and settled decisions — `C:\джава\NIDAA_MASTER_PLAN.md`
  (deliberately **not** in the repository; the session copy and this one are byte-identical)
- what each gate found and the owner's decisions after Gates 4 and 5 — `docs/gates/GATE-3.md`, `docs/gates/GATE-4.md`, `docs/gates/GATE-5.md`, `docs/gates/GATE-6.md`
- architecture decisions — `docs/adr/` (005 so far; 001–004 are planned for Phase 7, see `docs/adr/README.md`)
- security reasoning — `docs/SECURITY.md`; deferred items — `FUTURE_WORK.md`
- what the gate scripts check — `scripts/gate/README.md`; configuration keys — `README.md` "Configuration", "Testing"

There is no `PROJECT_STATUS.md` in this repository or in `C:\джава`; section 4 below is the
status record.

---

## 1 · Local environment

**Machine.** Windows 11, scripts run in Git Bash. `core.autocrlf=true`, git user `Khalil-Pal`.

| Tool | Where / version | Note |
|---|---|---|
| JDK 17.0.17 (Temurin) | `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\` | `java` on `PATH` is **1.8.0_401**. Run the jar with `"$JAVA_HOME/bin/java"`, never bare `java`. `./mvnw` (Maven 3.9.14) picks up `JAVA_HOME` and is fine |
| PostgreSQL 17 | `"/c/Program Files/PostgreSQL/17/bin/psql.exe"`, user `postgres`, port 5432 | `psql` is **not on PATH**; every gate script takes the path in `PSQL` |
| Node v24.17.0 | on PATH | CI uses Node 20 |
| Python 3.14.3 | on PATH (`python`) | used by `smtp-sink.py` and inside `acceptance.sh` for JSON |
| Chrome | `C:/Program Files/Google/Chrome/Application/chrome.exe` | the node gate scripts take it in `CHROME` (that path is also their default) |
| Node gate tools | `C:\джава\gate-tools\node_modules` (puppeteer-core 23.11.1, axe-core, lighthouse, sharp) | outside the repo on purpose; export `NODE_PATH=/c/джава/gate-tools/node_modules`. If it is gone: `mkdir -p /c/джава/gate-tools && cd /c/джава/gate-tools && npm install puppeteer-core@23.11.1 axe-core lighthouse` (npm creates package.json + node_modules in an empty folder; verified) |

**Not installed:** Docker (`docker: command not found`) and the GitHub CLI `gh`.
- Docker → DEP-3 was recorded **NOT RUN** and `docker compose up --build` is a **Gate 7 blocker**; what was verified instead is in `docs/gates/GATE-4.md`, row DEP-3. Do not try to run it here.
- `gh` → CI status via `curl -s "https://api.github.com/repos/Khalil-Pal/Nidaa-project/actions/runs?branch=audit-remediation&per_page=3"` (works unauthenticated). A job **log** returns 403 without auth; the stored HTTPS credential from `git credential fill` works as an `Authorization: Bearer` token — use it in a script, never print it.

**Secrets.** `.env` at the repo root (git-ignored) holds exactly three keys: `DB_PASSWORD`,
`JWT_SECRET`, `MAIL_PASSWORD`. It is read by the app (`spring.config.import=optional:file:.env[.properties]`),
by `PersistenceTestSupport` (DB password for the reachability probe) and by `fresh-db.sh`.
`src/main/resources/application.properties` is git-ignored and is the example file with its header
comments trimmed — same key set (diffed). CI copies the example. Never echo either file's values;
`sed -E 's/=.*/=<set>/' .env` is the safe way to look at key names.

**Environment variables** (all from `application.properties.example`, verified):

| Purpose | Variable | Default | Used by |
|---|---|---|---|
| required, fail-fast | `DB_PASSWORD`, `JWT_SECRET`, `MAIL_PASSWORD` | none — startup fails with `Could not resolve placeholder` | app, `./mvnw test` (via `.env`) |
| database | `DB_URL` | `jdbc:postgresql://localhost:5432/Web_DB?stringtype=unspecified` — **the dev database** | app; **always set it for gate/jar runs** |
| | `DB_USERNAME` | `postgres` | app, persistence tests |
| tests | `NIDAA_TEST_DB_URL` | `...5432/nidaa_test?stringtype=unspecified` | `PersistenceTestSupport` (`@DataJpaTest`, skipped — not failed — when unreachable; CI fails the job on a skip) |
| server | `SERVER_PORT` | `8081` | app |
| | `CORS_ORIGINS` | `http://localhost:8081` | app |
| rate limit | `RATELIMIT_ENABLED` / `RATELIMIT_AUTH_PER_MINUTE` / `RATELIMIT_API_PER_MINUTE` | `true` / `5` / `100` | gate runs need `RATELIMIT_AUTH_PER_MINUTE=50` |
| JWT | `JWT_EXPIRATION` / `JWT_REFRESH_EXPIRATION` | `900000` / `604800000` ms | app |
| mail | `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_AUTH` / `MAIL_SSL` | `smtp.example.com` / `465` / `noreply@example.com` / `true` / `true` | gate runs: `127.0.0.1` / `1025` / — / `false` / `false` (the sink) |
| | `NIDAA_ADMIN_EMAIL` | `admin@example.com` | app |
| logging | `LOGGING_LEVEL_COM_HUMANITARIAN_PLATFORM` | per profile in `logback-spring.xml` | app |

Build needs nothing beyond `JAVA_HOME`. `./mvnw test` needs `.env` present (for the three
required keys) and `nidaa_test` reachable, otherwise the 64 persistence tests skip.

**Databases on this machine** (`psql -l`, 2026-09-16):

| Database | Role | State | Rule |
|---|---|---|---|
| `Web_DB` | **dev** — the default `DB_URL` | owner's data: 4 users, 7 help requests. Created before Flyway → `flyway_schema_history` = `18:BASELINE, 19:SQL` (V19 was applied by the app) | never run a gate script against it; every new `Vn` migration is applied to it the next time the app starts without `DB_URL` |
| `nidaa_gate` | **gate** | rebuilt from V1 by `fresh-db.sh` on every gate run; right now holds this session's fixtures (`fx-*`, `gate-*`) | `acceptance.sh` needs it **fresh** each run |
| `nidaa_test` | **`./mvnw test`** persistence tests | rolled back per test, empty. Locally also `18:BASELINE, 19:SQL`, whereas CI builds it from V1 on an empty container | so a green local `./mvnw test` does not prove a migration runs from scratch — `fresh-db.sh` does, and CI does |

**Ports:** app `8081` (acceptance also hits `127.0.0.2:8081`, a second loopback address, for
the rate-limit burst), mail sink `1025`, PostgreSQL `5432`. Nothing else.

---

## 2 · Running a full gate — the exact commands

This sequence was run in this order on 2026-09-16 and produced the numbers in §4. Run it from
Git Bash. Order matters: the database is rebuilt **before** the app starts (Flyway runs at
start-up), acceptance runs against the **fresh** database, the browser fixtures are added
**after** acceptance. Whole run ≈ 10 minutes; the N-1 browser section alone waits for the real
60-second poll.

```bash
cd /c/джава/platform
export PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe"

# 1. fresh gate database from the migrations (G2/DEP-1) + the G3 trigger/function/view inventory
bash scripts/gate/fresh-db.sh nidaa_gate            # needs DB_PASSWORD; reads it from .env when unset

# 2. the jar (kill any running app java first, see §3 item 14)
./mvnw -q -DskipTests package

# 3. mail sink + app against nidaa_gate; secrets come from .env and are never printed
python scripts/gate/smtp-sink.py 1025 > /tmp/smtp.log 2>&1 &  echo $! > /tmp/smtp.pid
set -a; . ./.env; set +a
DB_URL="jdbc:postgresql://localhost:5432/nidaa_gate?stringtype=unspecified" \
RATELIMIT_AUTH_PER_MINUTE=50 MAIL_HOST=127.0.0.1 MAIL_PORT=1025 MAIL_SSL=false MAIL_AUTH=false \
"$JAVA_HOME/bin/java" -jar target/platform-1.0.0.jar > /tmp/app.log 2>&1 &  echo $! > /tmp/app.pid
for i in $(seq 1 90); do curl -s -o /dev/null http://127.0.0.1:8081/api/dashboard/public-stats && { echo "up after ${i}s"; break; }; sleep 1; done

# 4. API acceptance (G4/G5). JWT_SECRET (now exported from .env) enables the S-15 expired-token check,
#    otherwise it prints N-A. BASE_ALT must be a second loopback address.
BASE=http://127.0.0.1:8081 BASE_ALT=http://127.0.0.2:8081 DB=nidaa_gate \
bash scripts/gate/acceptance.sh | tee /tmp/acceptance.txt | grep -E '^  (FAIL|N-A)|RESULT'

# 5. fixtures for the browser scripts (they need accounts, a stored-XSS row, a HIGH request, organizations)
BASE=http://127.0.0.1:8081; DB=nidaa_gate; PW='Gate-Pass-2026!'
sql() { "$PSQL" -U postgres -d "$DB" -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '\r'; }
tok() { curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"$2\"}" | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])"; }
reg() { # email role -> verified account (providers still need approval)
  curl -s -o /dev/null -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"fullName\":\"Fixture $2\",\"email\":\"$1\",\"password\":\"$PW\",\"phone\":\"+1$RANDOM$RANDOM\",\"role\":\"$2\"}"
  curl -s -o /dev/null -X POST "$BASE/api/auth/register/verify" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"code\":\"$(sql "select code from pending_registrations where email='$1'")\"}"
}
approve() { curl -s -o /dev/null -w "approve $1 -> %{http_code}\n" -X PUT "$BASE/api/admin/approve/$(sql "select user_id from users where email='$1'")" -H "Authorization: Bearer $A"; }
# the first administrator the documented way (SQL); the hash is bcrypt of Audit-Test-2026
sql "INSERT INTO users (email,password_hash,full_name,role,is_verified,is_active,is_locked)
     VALUES ('fx-admin@example.test','\$2a\$10\$qULuA3kpH4oMKjMFTMSUHO6IrfuFIH6O8AhHm0DPkuzCp69Rb1i32','Fixture Admin','ADMIN',true,true,false)" >/dev/null
reg fx-bene@example.test beneficiary
reg fx-vol@example.test volunteer
reg fx-org@example.test organization
reg fx-org2@example.test organization        # stays unapproved: admin-approvals.html then renders the organization pill
A=$(tok fx-admin@example.test Audit-Test-2026)
approve fx-vol@example.test
approve fx-org@example.test
# the stored-XSS row (S-2), which is also the HIGH request the contrast check needs; the API refuses this title by design
sql "INSERT INTO help_requests (beneficiary_id,title,description,help_type,urgency_level,status,priority_score)
     VALUES ((select user_id from users where email='fx-bene@example.test'), '<img src=x onerror=alert(1)>', 'planted', 'FOOD', 'HIGH', 'PENDING', 40)" >/dev/null

# 6. browser, accessibility and keyboard checks (G5, F-5). Run them from OUTSIDE the repo:
#    a11y-audit.js writes a11y-<label>.json and lighthouse.js writes lighthouse-<label>.json into the CWD,
#    and *.json is not git-ignored.
mkdir -p /tmp/gate && cd /tmp/gate
export BASE=http://127.0.0.1:8081 DB=nidaa_gate PGUSER=postgres NODE_PATH=/c/джава/gate-tools/node_modules
export CHROME="C:/Program Files/Google/Chrome/Application/chrome.exe"
export ADMIN_EMAIL=fx-admin@example.test ADMIN_PASSWORD=Audit-Test-2026
export BENE_EMAIL=fx-bene@example.test BENE_PASSWORD='Gate-Pass-2026!'
export VOL_EMAIL=fx-vol@example.test   VOL_PASSWORD='Gate-Pass-2026!'
node /c/джава/platform/scripts/gate/browser-checks.js  | tee browser.txt  | grep -E '^  FAIL|RESULT'   # REG reads the code from the DB: needs DB/PGUSER/PSQL
node /c/джава/platform/scripts/gate/a11y-audit.js gate5 | tee a11y.txt     | tail -1                  # 18 pages; exit 1 on any violation
node /c/джава/platform/scripts/gate/keyboard-checks.js | tee keyboard.txt | grep -E '^  FAIL|passed'
# optional, F-3/PF-1 only: node /c/джава/platform/scripts/gate/lighthouse.js http://127.0.0.1:8081/index.html gate5

# 7. invariants (G3): every count must be 0
"$PSQL" -U postgres -d nidaa_gate -v ON_ERROR_STOP=1 -f /c/джава/platform/scripts/gate/invariants.sql

# 8. fail-fast check (G2), only when the configuration changed: move .env aside, start the jar with
#    JWT_SECRET unset on another SERVER_PORT, expect "Could not resolve placeholder 'JWT_SECRET'"; restore .env

# 9. stop; nothing must stay listening on 8081/1025
kill "$(cat /tmp/app.pid)" "$(cat /tmp/smtp.pid)"
cd /c/джава/platform && git status --short        # must be empty: the gate leaves no files in the tree
```

What each script needs (verified against the scripts' headers and their `process.env` / `${VAR}` reads):

| Script | Needs | Output |
|---|---|---|
| `fresh-db.sh [db]` | `PSQL`; `PGUSER` (default postgres); `DB_PASSWORD` or `.env`; runs `./mvnw flyway:migrate` | PASS/FAIL lines (history V1–V11, V13–V23), then the G3 inventory. Current inventory (unchanged through Gate 6): one function `update_updated_at_column` and six `update_*_updated_at` BEFORE UPDATE triggers, no views |
| `smtp-sink.py [port]` | Python; port free | one `MESSAGE from / to / subject` line per mail. Registration is transactional with the verification e-mail, so without a sink (or real SMTP) nobody can register |
| `acceptance.sh` | app on a **fresh** `nidaa_gate` with `RATELIMIT_AUTH_PER_MINUTE=50`; `BASE`, `BASE_ALT`, `DB`, `PSQL`; `JWT_SECRET` for S-15 | 238 checks; exit non-zero on any FAIL |
| `browser-checks.js` | app; `ADMIN_/BENE_/VOL_ EMAIL+PASSWORD` of approved accounts; `DB`, `PGUSER`, `PSQL` (REG reads `pending_registrations`); `CHROME`; `NODE_PATH` | 78 checks in sections S-2 4, L-1 4, F-4 4, REG 11, VER 7, N-1 7, W-1 4, R-1 6, GAP-1 5, ON-2 8, CM-1 7, CS-1 11 |
| `a11y-audit.js <label>` | app; `ADMIN_*`, `BENE_*`; `CHROME`; `NODE_PATH` (axe-core) | per-page line + totals; writes `a11y-<label>.json` in the CWD |
| `keyboard-checks.js` | same as a11y | 19 checks |
| `lighthouse.js <url> <label>` | `NODE_PATH` (lighthouse); Chrome path is hard-coded in the script | writes `lighthouse-<label>.json` in the CWD |
| `scripts/perf/seed-requests.js`, `load-test.js` (PF-1) | app on a **fresh** database with `RATELIMIT_ENABLED=false`; `BASE`, `DB`, `PGUSER`, `PSQL`; the seeder prints the accounts the load test needs | 10,000 requests through the API, then p50/p95/p99 and throughput per endpoint at 1/10/50 users into a JSON file. Not part of a gate run; see `docs/PERFORMANCE.md` |
| `scripts/ut/setup-participants.sh` (UT-1) | app on a **fresh** `nidaa_ut` with `RATELIMIT_AUTH_PER_MINUTE=50`; `BASE`, `DB`, `PGUSER`, `PSQL` | the six session accounts and the starting queue, then the facilitator's sheet. Refuses to run on a database that already has users |
| `invariants.sql` | `psql -f` against the gate DB | 17 rows, every `violations` 0 |

Record a gate run in `docs/gates/GATE-<n>.md` in the layout of `GATE-4.md`/`GATE-5.md` (item,
PASS/FAIL/NOT RUN, evidence), then push.

---

## 3 · Traps already hit — do not reintroduce

1. **`mvnw` file mode.** The first CI run failed with `./mvnw: Permission denied` because `mvnw`
   was tracked as `100644` from the Windows checkout. Fixed in `c581b90` with
   `git update-index --chmod=+x mvnw`; now `100755`, `.gitattributes` pins it to LF. **The same
   condition exists today for `scripts/gate/*.sh`: tracked `100644`** (executable on disk only).
   Nothing on Linux runs them yet, so they are left alone — always invoke them as
   `bash scripts/gate/x.sh`, and chmod the index before ever calling them from CI.
2. **`.gitignore` has `*.sql`.** `scripts/gate/invariants.sql` had never been tracked because of
   it. Negations now exist for `src/main/resources/db/migration/*.sql`, `scripts/gate/*.sql` and
   `database/migrations/*.sql` (that directory holds only a README today). A `.sql` file anywhere
   else is silently ignored — `git status` will not show it. Before assuming a new file is in a
   commit: `git check-ignore -v <path>`.
3. **Checks that pass vacuously without fixture data.**
   - L-2 "crisis case routed" compared two counts that were both 0 when no psychologist was on
     duty; `acceptance.sh:252` now also asserts the routed count is exactly 2. Keep writing gate
     checks against an explicit expected value, not `count == count`.
   - The a11y audit reported 0 violations for weeks; the `#ea580c` contrast failures (fixed in
     `83bae45`, F-5) appear **only** when a HIGH-urgency request and organization pills are
     rendered. That is why the fixtures in §2 plant a HIGH request and create an approved and a
     pending organization. An audit on empty pages proves little.
   - `browser-checks.js` needs an **approved** volunteer (`approve fx-vol@...`); N-1/R-1 use it.
   - `acceptance.sh` on a stale `nidaa_gate` reports 409s/duplicates as failures that are not
     bugs. Always `fresh-db.sh` first.
4. **The default `DB_URL` is the dev database `Web_DB`.** A jar started without `DB_URL` runs
   Flyway against the owner's data. Always pass `DB_URL` for gate runs (§2 step 3).
5. **Auth rate limit** is 5/min per IP by default; the gate flow needs ~20 logins, so the gate app
   runs with `RATELIMIT_AUTH_PER_MINUTE=50`. A 429 looks like a failed login in the scripts.
6. **`java` on PATH is Java 8**; `JAVA_HOME` is 17. `"$JAVA_HOME/bin/java" -jar ...` always.
7. **`node --test src/test/js` (a directory) fails on Node 24** (`test failed`); run the file:
   `node src/test/js/nidaa-common.test.js` or `node --test src/test/js/nidaa-common.test.js`
   (what CI runs, on Node 20).
8. **Line endings.** The index is LF throughout (`core.autocrlf=true` normalises on commit), but
   49 tracked files are CRLF in the working tree, among them `PlatformApplication.java`,
   `DataSeeder.java`, `DashboardController.java`, `HelpRequestV1Controller.java`,
   `ApiResponse.java`, `ReportRepository.java`, `HELP.md` and the Postman files
   (`git ls-files --eol | grep w/crlf`). Exact-string edits must reproduce the file's own
   endings; a script that writes LF into a CRLF file leaves a mixed file that the diff hides.
9. **The Bash tool mangles backslashes inside heredocs.** Doubled backslashes collapse, `\u`
   and emoji escapes in generated JS came out wrong, and writing this very file through a
   heredoc failed to parse. Write edit scripts, generated code and documents containing
   backslashes with the Write tool, or use literal characters; verify with `grep` after writing.
10. **Hibernate native queries read `::` as a parameter marker.** `type::text` in
    `createNativeQuery` fails; use `CAST(type AS text)` (`NotificationPersistenceTest.java:43,88`).
11. **Mockito `@InjectMocks` passes `null` for constructor arguments that have no `@Mock`.** Every
    service that took `NotificationService` (N-1) broke its unit tests with an NPE until
    `@Mock NotificationService` was added. Any new constructor dependency needs the same in
    every `@InjectMocks` test of that service.
12. **PostgreSQL stores microseconds, Java keeps nanoseconds.** Compare round-tripped timestamps
    with `truncatedTo(ChronoUnit.MILLIS)` (`NotificationPersistenceTest.java:95`).
13. **Node gate scripts write JSON into the current directory** (`a11y-<label>.json`,
    `lighthouse-<label>.json`) and `*.json` is not ignored. Run them from outside the repo (§2
    step 6) or move the files out before committing.
14. **The jar.** Re-running `package` while the gate app still held `target/platform-1.0.0.jar`
    produced a jar without a manifest (`no main manifest attribute`). Kill the java first.
    Maven leaves an up-to-date jar untouched (an unchanged timestamp after a no-change build is
    normal, seen today); after code changes, check the timestamp or use `clean package`.
15. **Browser sign-up check in a shared context.** `register.html` redirected because the
    browser still carried the previous section's session; REG and VER run in an isolated
    `browser.createBrowserContext()` (`browser-checks.js:167,184`) and clear `localStorage`
    between roles. New sections that start signed-out need the same.
16. **Flyway baseline.** A database that existed before Flyway is baselined at 18 and only V19+
    run on it (`Web_DB`, local `nidaa_test`); an empty database gets V1–V19 (V12 was never
    issued — documented). A new migration must work in both situations: `fresh-db.sh` proves the
    empty case, starting the app once against `Web_DB` proves the baselined case. Migrations are
    idempotent `DO`-block style (see V19); add each new one to `database/migrations/README.md`.
17. **The Master Plan stays out of the repository** (owner's choice). Read it from
    `C:\джава\NIDAA_MASTER_PLAN.md`; do not commit it. `NIDAA_7_PHASES_IMPLEMENTATION_REPORT.md`
    is never edited (plan §1).

---

## 4 · Current working state (2026-09-18, after Gate 6)

- **Branch** `audit-remediation`, **HEAD = the Gate 6 record commit** (`git log -1`), working tree
  **clean**, **everything pushed**, CI green on every push of this phase (the persistence tests
  run on Linux against a database built from V1).
- `stash@{0}: On main: java-upgrade-precheck-20260419153813` predates the remediation. Not
  ours; leave it.
- **Phase 6 is complete except UT-1's sessions, and Gate 6 passed** (`docs/gates/GATE-6.md`,
  no FAIL, one NOT RUN). One commit per task ID since Gate 5: `4e257d0` CS-1 (per-session
  records, V22), `bc875d0` W-1 justification, `fcd33bf` ON-2 identity principle, `c5e95b3`
  DOC-FW, `b89e7ab` (Gate 5 decisions), `be6ad0b` EV-1 (**superseded**), `fc5ebfe` UT-1,
  `3760fef` GAP-1, `fe8f154` GAP-2, `4a90354` DOC-SCORING, `8490dda` PF-1 harness, `c046433`
  EV-1 (corrected), `671ea47` PF-1 measurements.
- **UT-1 is the one thing left in Phase 6 and it needs people.** Everything to run a session is
  ready and verified (`docs/evaluation/UT-1-user-testing.md`, the scripts and instruments in
  `docs/evaluation/ut-1/`, `scripts/ut/setup-participants.sh`). `ut-1/results.md` is a template
  whose first line says NOT YET RUN. **Do not invent participants, findings or SUS scores.**
  After the sessions: fill in the template, fix the top three findings one commit each with
  `Audit-Ref: UT-1`, re-test with new people, and update GATE-6.md's UT-1 row.
- **Owner decisions after Gate 5** (all implemented, do not re-ask): CS-1 overturned to one
  record per session (V22 dropped V20's UNIQUE; `GET` returns a list; a case stays ASSIGNED
  until the psychologist completes it; every privacy rule kept); W-1 kept with a domain
  justification (aid has a delivery journey, support does not); ON-2 kept, with one identity
  principle in SECURITY.md §3.2; `locations` neither seeded nor dropped, written up as the
  third future pipeline; **DM-1 cancelled**, moved to FUTURE_WORK.md permanently; Phase 6
  approved with EV-1 mandatory. Then, after a supervisor review: GAP-1, GAP-2 and
  `docs/SCORING.md`, all built before the experimental runs.
- **Judgement calls made in Phase 6** (mine, in the commits and GATE-6.md G6): a decline is
  `DECLINED` not `CANCELLED`; non-parties get 404 on the decline endpoint; the third decline
  escalates instead of rematching; the attention flag is raised once by a guarded UPDATE and
  cleared by any assignment; the sweep is not `@Transactional` so one failing request isolates;
  the simulation charges the 30-minute sweep interval because the platform does.
- **Open for the owner**: the UT-1 sessions; the Docker run (`docker compose up --build`, Gate 7
  blocker since Gate 4); **EV-2** (PostGIS benchmark) — still optional and still "ask first".
- **Contradictions already recorded** (don't re-flag): `reports.volunteer_id` is NOT NULL so
  completion reports are volunteer-only; a rejected application has no user row so rejection is
  e-mail only; the plan's "both transition maps" (W-1) are the one shared `RequestTransitions`
  since C-3 plus the two page copies.
- **Latest migration is V23** (V22 drops the one-consultation-per-case UNIQUE; V23 adds
  `help_requests.needs_attention*` and four indexes for GAP-1/GAP-2); the next is **V24**.
  V12 was never issued.
- **Baseline numbers** (Gate 6 run, §2 commands): `./mvnw clean test` **400 tests, 0 failures,
  0 errors, 0 skipped** (83 persistence); `node --test src/test/js/nidaa-common.test.js` 1/1;
  `acceptance.sh` **238/238**; `browser-checks.js` **78/78**; `a11y-audit.js` 18 pages, **0 axe
  / 0 CSP / 0 inline**; `keyboard-checks.js` **19/19**; `invariants.sql` **17** checks all 0;
  `fresh-db.sh` history = the files (V1–V11, V13–V23); Lighthouse index.html mobile 90 /
  LCP 2.9 s, desktop 82 / 2.0 s, images 142 KB mobile. Any drop is a regression.
- **EV-1 reproduces from a seed.** `MatchingStudyMain --out docs/evaluation/ev-1 --reps 10
  --seed 20260917` prints `checksum 9c0f4b2f6e68ef7d` and regenerates every committed file byte
  for byte (about 5 minutes; no database). If a change to the matching or priority code moves
  that checksum, the chapter's numbers are stale and must be regenerated **and** re-read: the
  discussion quotes them.
- **Databases now:** `nidaa_gate` holds the Gate 6 fixtures (rebuild before the next acceptance
  run); `nidaa_test` and `Web_DB` are at V23 on their `18:BASELINE` history; `nidaa_perf` holds
  the PF-1 data (121,495 requests — drop or rebuild it before reusing). `nidaa_ut` was dropped
  after the setup script was verified. **Processes:** nothing of ours is running; 8081, 1025 and
  8090 are free.
- **Commit format in use:** `type(TASK-ID): summary`, body explaining what and why, then
  `Audit-Ref: <ID>`, then `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. One commit
  per task ID — if a change touches two task IDs, split it rather than combining them.

## 5 · Tried and did not work — do not retry

- **Docker on this machine** — not installed; DEP-3 stays NOT RUN until a Docker host runs
  `docker compose up --build` (Gate 7).
- **`gh`** — not installed; **unauthenticated job-log download** — 403. Use the runs endpoint
  (works) and the stored git credential as a Bearer token for logs.
- **`node --test` on the test directory** — fails on Node 24; run the file.
- **Generating page scripts, edit scripts or this document through Bash heredocs** —
  backslashes and escapes were mangled or the heredoc failed to parse; use the Write tool for
  anything containing backslashes.
- **`type::text` in a Hibernate native query** — parsed as a parameter; `CAST(type AS text)`.
- **Re-packaging the jar while the app was running** — jar without a manifest; kill first.
- **Sign-up browser check in the shared context** — redirected by the leftover session;
  isolated context.
- **Creating the stored-XSS fixture through the API** — the API refuses that title by design
  (S-2); it is planted by SQL, as the gate README says.
- **Comparing PostgreSQL timestamps for exact equality after a round trip** — nanosecond vs
  microsecond precision; truncate to millis.
- **`@InjectMocks` without a mock for a new constructor dependency** — NPE in every affected
  unit test; add the `@Mock`.
- **Running `npm install` inside `scripts/gate`** (the gate README's older suggestion) would
  leave an untracked `node_modules` in the repo: `.gitignore` has no `node_modules` rule
  (verified). Use `C:\джава\gate-tools` and `NODE_PATH` instead.

---

## 6 · How to resume

Paste this into a new Claude Code session opened in `C:\джава\platform`:

```
Continue the Nidaa audit remediation on branch audit-remediation (tree clean, everything pushed).
Read, in this order, before doing anything: C:\джава\NIDAA_MASTER_PLAN.md (the plan; not in
the repo — every standing rule in its §1 applies), SESSION_HANDOFF.md (environment, gate
commands, traps, current state), docs/gates/GATE-6.md (what Phase 6 delivered, what is NOT RUN,
the judgement calls), docs/gates/GATE-5.md and GATE-4.md (owner decisions), FUTURE_WORK.md.

Phase 6 is complete except UT-1's sessions, and Gate 6 passed. Phase 7 (FR-1 feature freeze,
the documentation set, the diagrams, defence preparation) starts only on the owner's word.
Three things wait for the owner: the UT-1 sessions (five to ten people; everything to run them
is ready, and nothing in the repository may report a finding or a SUS score until they have
happened), the Docker run (docker compose up --build on a machine with Docker, the Gate 7
blocker), and EV-2 (PostGIS benchmark, optional, ask first).

First action: confirm the CI run for HEAD is green (curl the actions API as SESSION_HANDOFF.md
§1 describes; no gh here), then ask the owner which of those, or Phase 7, to start.

Working rules that apply to every task: one commit per task ID with `Audit-Ref: <ID>` and the
Co-Authored-By line; ./mvnw test green (baseline 400/0) after every task; the gate scripts run
exactly as SESSION_HANDOFF.md §2 shows; never put secrets in tracked files or print them;
never touch NIDAA_7_PHASES_IMPLEMENTATION_REPORT.md; if the schema or code contradicts the
plan, stop and say so rather than improvise. If you change matching or priority code, re-run
EV-1 and check its checksum — the chapter quotes its numbers. Report each task as what changed
/ how it was verified / contradictions found.
```
