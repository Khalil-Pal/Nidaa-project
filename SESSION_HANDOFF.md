# Session handoff — Nidaa audit remediation

Written 2026-09-16 at HEAD `dc4d243` on `audit-remediation`, for the next Claude Code
session. Every statement here was checked on this machine on that date (commands run,
files read), not recalled.

This file holds only what a new session could **not** learn from the repository. It does
not restate:

- the plan, phases, gates, standing rules and settled decisions — `C:\джава\NIDAA_MASTER_PLAN.md`
  (deliberately **not** in the repository; the session copy and this one are byte-identical)
- what each gate found and the owner's decisions after Gate 4 — `docs/gates/GATE-3.md`, `docs/gates/GATE-4.md`
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
| `fresh-db.sh [db]` | `PSQL`; `PGUSER` (default postgres); `DB_PASSWORD` or `.env`; runs `./mvnw flyway:migrate` | PASS/FAIL lines, then the G3 inventory. Current inventory: one function `update_updated_at_column` and six `update_*_updated_at` BEFORE UPDATE triggers, no views |
| `smtp-sink.py [port]` | Python; port free | one `MESSAGE from / to / subject` line per mail. Registration is transactional with the verification e-mail, so without a sink (or real SMTP) nobody can register |
| `acceptance.sh` | app on a **fresh** `nidaa_gate` with `RATELIMIT_AUTH_PER_MINUTE=50`; `BASE`, `BASE_ALT`, `DB`, `PSQL`; `JWT_SECRET` for S-15 | 139 checks; exit non-zero on any FAIL |
| `browser-checks.js` | app; `ADMIN_/BENE_/VOL_ EMAIL+PASSWORD` of approved accounts; `DB`, `PGUSER`, `PSQL` (REG reads `pending_registrations`); `CHROME`; `NODE_PATH` | 43 checks in sections S-2 4, L-1 4, F-4 4, REG 11, VER 7, N-1 7, R-1 6 |
| `a11y-audit.js <label>` | app; `ADMIN_*`, `BENE_*`; `CHROME`; `NODE_PATH` (axe-core) | per-page line + totals; writes `a11y-<label>.json` in the CWD |
| `keyboard-checks.js` | same as a11y | 19 checks |
| `lighthouse.js <url> <label>` | `NODE_PATH` (lighthouse); Chrome path is hard-coded in the script | writes `lighthouse-<label>.json` in the CWD |
| `invariants.sql` | `psql -f` against the gate DB | 15 rows, every `violations` 0 |

Record the run in `docs/gates/GATE-5.md` in the layout of `GATE-4.md` (item, PASS/FAIL/NOT RUN,
evidence), then push.

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

## 4 · Current working state (2026-09-16 20:40 +03:00)

- **Branch** `audit-remediation`, **HEAD `dc4d243`**, working tree **clean** (checked before and
  after today's gate run). **Ahead of `origin/audit-remediation` by 3 commits, not pushed:**
  `a68121c docs(DATABASE)`, `8d7a9f9 feat(N-1)`, `dc4d243 feat(R-1)`. `origin` is at `fef004e`;
  CI is green there (run 35017220441, as on the two runs before it). The three unpushed
  commits have therefore **never run in CI** — pushing is the first action of the next
  session (the plan says push after the phase; nothing forbids pushing earlier).
- `stash@{0}: On main: java-upgrade-precheck-20260419153813` predates the remediation. Not
  ours; leave it.
- **No task is half-done.** R-1 is fully committed; **CS-1 has no code** (no V20, `Consultation`
  entity untouched, no controller/service/tests).
- **Phase 5 order, given by the owner on 2026-09-15** (chat, not in the plan): N-1 ✅, R-1 ✅,
  then **CS-1 → AGG-1 → W-1 → ON-2 → CM-1**, then DOC-FW, then Gate 5. **DM-1 is on hold** — do
  not start it; the plan already says ask first. "Confirm D-3 landed before AGG-1" is done:
  V18 is in every `flyway_schema_history` here, ratings are nullable.
- **Owner decisions after Gate 4** (register.html fix, ADR 005 + amended invariant, credential
  verification as its own admin action with `is_on_duty=false` on approval, DEP-3 NOT RUN
  accepted, Gate 7 blocked) are implemented and recorded in `docs/gates/GATE-4.md`; do not re-ask.
- **Contradictions already recorded** (in the R-1/N-1 commit messages and `FUTURE_WORK.md`):
  `reports.volunteer_id` is NOT NULL, so completion reports are volunteer-only and organizations
  get 400; a rejected application has no user row left, so rejection is e-mail only.
- **CS-1 groundwork already verified in the code** (facts a new session would otherwise
  re-derive; the plan section is "CS-1 · Consultation records"):
  - `model/Consultation.java:28` maps `format` as `varchar(50)` while the column is the
    `consultation_format` enum (CHAT/AUDIO/VIDEO) — the D-4 drift fix is `columnDefinition = "consultation_format"`;
    `:41` maps `topics_discussed` (`text[]`) as a `String` with `columnDefinition = "_text"`.
  - `PsychologicalRequestRepository.updateStatusNative` (`:56`) never writes
    `psychological_requests.completed_at`; `FUTURE_WORK.md:50` assigns that to CS-1 — remove the
    entry when done.
  - `consultations` has no `assignment_id`; the plan requires `BIGINT NULL REFERENCES assignments(assignment_id)` (→ V20).
  - Latest migration is **V19**; the next is **V20**.
  - Not decided by anyone yet (my proposals, not owner decisions): whether a
    `GET .../consultation` exists and what an admin sees; that `notes_for_psychologist` is
    **omitted** (not nulled) from every non-psychologist response; that feedback on an
    anonymous request carries no name or `beneficiaryId` in the response or the notification.
- **Baseline numbers** (all from today's run, §2 commands): `./mvnw test` **323 tests, 0
  failures, 0 errors, 0 skipped** (64 persistence); `node src/test/js/nidaa-common.test.js` passes;
  `acceptance.sh` **139/139**; `browser-checks.js` **43/43**; `a11y-audit.js` 18 pages, **0 axe
  / 0 CSP / 0 inline**; `keyboard-checks.js` **19/19**; `invariants.sql` 15 checks all 0;
  `fresh-db.sh` history = the files (V1–V11, V13–V19). Any drop is a regression.
- **Databases now:** `nidaa_gate` holds today's fixtures (rebuild before the next acceptance
  run); `Web_DB` untouched by any gate; `nidaa_test` empty. **Processes:** nothing of ours is
  running (the two `java.exe` are VS Code's Java language server); 8081 and 1025 are free.
- **Commit format in use:** `type(TASK-ID): summary`, body explaining what and why, then
  `Audit-Ref: <ID>` (omitted only on owner-requested docs commits such as `a68121c`), then
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. One commit per task ID.

---

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
Continue the Nidaa audit remediation on branch audit-remediation (HEAD dc4d243, tree clean).
Read, in this order, before doing anything: C:\джава\NIDAA_MASTER_PLAN.md (the plan; not in
the repo — every standing rule in its §1 applies), SESSION_HANDOFF.md (environment, gate
commands, traps, current state), docs/gates/GATE-4.md (owner decisions after Gate 4),
FUTURE_WORK.md.

First action: push the three unpushed commits (a68121c, 8d7a9f9, dc4d243) and confirm the CI
run on GitHub is green (curl the actions API as SESSION_HANDOFF.md §1 describes; no gh here).

Then continue Phase 5 in the owner's order: CS-1 → AGG-1 → W-1 → ON-2 → CM-1 → DOC-FW → Gate 5
(full G1–G6 plus the Phase 5 items, re-run the G3 inventory; stop on any FAIL). DM-1 is on
hold — do not start it. Do not re-ask anything listed as settled in the plan or in GATE-4.md.
Start with CS-1 exactly as the plan's "CS-1 · Consultation records" section specifies, using
the groundwork facts in SESSION_HANDOFF.md §4; where §4 marks something as "not decided", make
the judgement call, state it in the report, and do not ask.

Working rules that apply to every task: one commit per task ID with `Audit-Ref: <ID>` and the
Co-Authored-By line; ./mvnw test green (baseline 323/0) after every task; the gate scripts run
exactly as SESSION_HANDOFF.md §2 shows; never put secrets in tracked files or print them;
never touch NIDAA_7_PHASES_IMPLEMENTATION_REPORT.md; if the schema or code contradicts the
plan, stop and say so rather than improvise. Report each task as what changed / how it was
verified / contradictions found.
```
