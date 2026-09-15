# Gate 4 — after Phase 4

Run on 15 September 2026 against commit `b2d90ce` (23 Phase 4 commits after Gate 3,
one per task ID). Tooling: `scripts/gate/` — `fresh-db.sh` (now Flyway),
`invariants.sql`, `smtp-sink.py`, `acceptance.sh` (98 API checks), `browser-checks.js`,
`a11y-audit.js`, `keyboard-checks.js`, `lighthouse.js`. Figures below are copied
from their output. One item could not be executed on the gate machine and is
marked **NOT RUN** rather than PASS; see G6. DEP-4 was NOT RUN at the time of the
first write-up and is now recorded from the GitHub Actions runs after the push.

## G1 · Build and test

| Check | Result | Evidence |
|---|---|---|
| Clean build, all tests pass | PASS | `./mvnw clean test`: 296 tests, 0 failures, 0 errors, 0 skipped (Gate 3: 264). `node --test`: pass |
| No test disabled, skipped or deleted | PASS | `grep -rn "@Disabled\|@Ignore" src/test` → 0; every surefire report `skipped="0"`; no test class removed this phase (two approval-shaped `UserPersistenceTest` cases were replaced by `UserApprovalPersistenceTest`, which exercises the same rows through the service) |
| New tests mapped to task IDs | PASS | `UserApprovalPersistenceTest` (A-2, C-4), `GlobalExceptionHandlerTest.errorBodiesUseTheResponseEnvelope` (P-2), `PsychologicalRequestPersistenceTest` (Q-1, UX-2), `StatisticsPersistenceTest` (Q-2), `HelpRequestPersistenceTest.guardedStatusUpdates…` and two slice tests (B-4), `SharedHelpersTest` (C-3), `RequestCorrelationFilterTest` (C-4), `PsychologistDutySecurityTest` (UX-2), `StaticPagesCspTest` (F-5), `AuthSecurityTest.webpImagesArePublic…` (F-3), `…apiExplorerIsReachable…` (DEP-2), `HelpRequestSecurityTest.everyResponseCarriesTheFourSecurityHeaders` (DEP-5) |

## G2 · Fresh environment

| Check | Result | Evidence |
|---|---|---|
| Every migration applies to an empty database in order | PASS | `fresh-db.sh nidaa_gate`: `flyway:migrate` applied V1…V18 (17 files); `flyway_schema_history` lists exactly `1 2 3 4 5 6 7 8 9 10 11 13 14 15 16 17 18`, no failed rows |
| Re-running is a no-op by exit code | PASS | second `flyway:migrate`: "Schema public is up to date. No migration necessary.", BUILD SUCCESS. (Gate 3's caveat about non-idempotent V1–V8 is closed: Flyway never re-runs an applied version) |
| App starts against the fresh database | PASS | `Started PlatformApplication in 5.9 s` with `DB_URL=…/nidaa_gate` |
| Smoke path register → verify → login → submit → accept → complete | PASS | `acceptance.sh`: beneficiary registered, code read from `pending_registrations`, verified; volunteer pending until admin approval, then logged in; request A submitted, accepted, completed with `completed_at` |
| App fails loudly without required variables | PASS | `.env` aside, no `JWT_SECRET`: `Could not resolve placeholder 'JWT_SECRET'`, exit 1, no `Started` line |
| `dropdb nidaa_gate` | done after the run | |

## G3 · Schema and code agreement

| Check | Result | Evidence |
|---|---|---|
| Every trigger, function and view expected and documented | PASS | inventory unchanged: 1 function (`update_updated_at_column`), 6 `updated_at` triggers, 0 views; identical on `nidaa_gate` and `Web_DB`; documented in `docs/DATABASE.md`. New this phase: the `flyway_schema_history` **table** (not a trigger/function/view), documented in `database/migrations/README.md` |
| No trigger writes a column the app also writes, or proven harmless | PASS | as at Gate 3 (`updated_at` only, BEFORE UPDATE, no decision reads it) |
| Inventory unchanged since last gate or explained | PASS | unchanged |
| Persisted == computed for every stored computed value | PASS | `HelpRequestPersistenceTest.priorityScoreIsStoredAsTheApplicationComputedIt` in G1 |
| Every enum column has a parameterised real-database test | PASS | `EnumRoundTripPersistenceTest` (categories, help type × urgency, support type × format) plus `UserPersistenceTest.roleColumnIsTheEnumAndJpaWritesThroughIt` for `users.role` (D-6) |
| Every `columnDefinition` names an existing type | PASS | `SchemaAgreementPersistenceTest` (D-4) in G1 |
| Invariant query set returns zero | **FAIL on one count — the Gate 3 open decision, unchanged** | `Web_DB`: all 14 zero. `nidaa_gate` after the full gate run: 13 zero, `unrestored_capacity_on_closed = 2`; both rows are **COMPLETED** assignments (ids 1 and 3), which by the documented design keep their capacity consumed. No CANCELLED row is unrestored. Decision still requested (see G6) |

## G4 · Regression (Phases 1–3 re-verified)

| Check | Result | Evidence |
|---|---|---|
| API acceptance from Gates 1–3 | PASS | `acceptance.sh`: 97 passed, 1 failed — the failure is the invariant above; every Phase 1–3 criterion holds (S-1, D-1, UX-1, S-6, S-15, S-4, S-5, P-1, S-10, S-8, S-7, S-9 burst engaged from a second address, ON-1, D-2, L-1, B-2, L-2, F-4). One check rewritten: F-2 "every API page includes the shared module once" looked for `fetch(` in the HTML; since F-5 the scripts are in `js/<page>.js`, so it now looks there (criterion unchanged, still holds) |
| Browser checks from Gate 3 | PASS | `browser-checks.js`: 12 passed — S-2 payload as literal text, no `<img>`, no dialog; L-1 granted → ASSIGNED, denied → manual-matching message; F-4 one refresh call, token replaced, no redirect — all under the strict CSP |
| Schema changes do not invalidate earlier assumptions | PASS | V17 (role enum) and V18 (nullable ratings) are exercised by the acceptance run (login, approval) and the invariants |

## G5 · Acceptance (Phase 4, verified now)

| Task | Result | Evidence |
|---|---|---|
| D-6 | PASS | `users.role` is `USER-DEFINED user_role` on the fresh database; `UserRepository` has no `approveUser/setActive/setLocked/updateLastLogin/updatePassword`; no `JdbcTemplate` in any controller; 13 login checks pass in acceptance |
| D-3 | PASS | `volunteers.rating` nullable, no default, `CHECK (rating IS NULL OR (rating >= 1 AND rating <= 5))`; the volunteer approved during the gate has `rating = NULL` |
| D-4 | PASS | G3 inventory re-run identical; `SchemaAgreementPersistenceTest` passes (every non-standard `columnDefinition` found in `pg_type`) |
| A-2 | PASS | `grep -rl JdbcTemplate controller/` → 0 files; approval and rejection ran through `UserApprovalService` (6 `USER_APPROVED` audit rows on the gate database) |
| P-2 | PASS | seven sampled endpoints (public stats, admin users/stats, pending pages, ranked dashboard, duty) all answer `{success, message, data}`; the 403 answers `{success, message}`; `GlobalExceptionHandlerTest` pins the error shape; no `data.data || data` reads remain in `js/`; the P-2 browser pass (13 checks: login, register step 1, forgot-password error text, four admin pages, dashboard, index counters) passes |
| Q-1 | PASS | no repository call inside any `Comparator`/`sorted()` (grep); `PsychologicalRequestPersistenceTest.caseLoadCounts…` runs the grouped query on the real schema |
| Q-2 | PASS | `/api/admin/stats` issued 13 `count`/`group by` statements and no table load (SQL log); `StatisticsPersistenceTest` |
| Q-3 | PASS | `/api/help-requests/pending?size=2` → page of 2, total 4; `/api/psychological-requests/pending?size=2` → page of 2, total 23; ranked dashboard carries `totalPending` and `materialTotalPages`; Q-3 browser pass (admin ranked table, psychologist dashboard and pending tab) 5/5 |
| B-4 | PASS | 12 concurrent `PUT …/status?status=COMPLETED` on one ASSIGNED request: **1 × 200, 11 × 409**; `HelpRequestPersistenceTest.guardedStatusUpdatesLetExactlyOneOfTwoRacingCallersWin` |
| UX-2 | PASS | browser: psychologist switches off duty on settings.html → crisis request stays PENDING; switches on → next crisis request ASSIGNED to that psychologist (id 4); status text names the unverified case |
| F-3 | PASS | `nidaa-logo.webp` 10.7 KB + `nidaa-hero.webp` 26.8 KB = 37.5 KB (target < 150 KB); no PNG/JPEG left at the root; Lighthouse on the gate app: mobile 98 / LCP 2.0 s, desktop 82 / LCP 2.1 s, images 139/178 KB (before F-3: 64/67, LCP 55.7 s, 13,553 KB) — recorded in `docs/PERFORMANCE.md` |
| F-5 | PASS | `a11y-audit.js`: 18 pages × 3 roles, **0 axe WCAG 2.1 A/AA violations (57 before), 0 CSP violations, 0 page errors, 0 inline handlers/scripts/`javascript:`**; `keyboard-checks.js`: 19/19 (Tab walks on every beneficiary-facing page, Enter/Space operate modals, pills, format cards, switches, tabs, password toggle; Escape closes; focus ring visible); header `script-src 'self' https://cdn.jsdelivr.net`, no `unsafe-inline`; `StaticPagesCspTest` guards it |
| DEP-1 | PASS | Flyway 10.22 applies V1–V18 to an empty database; `flyway_schema_history` matches the files; an existing database is baselined at 18 on first start (`Web_DB`, `nidaa_test`); Flyway-built and psql-built schemas are column-, constraint- and trigger-identical (271 / 97 / 6) |
| DEP-2 | PASS | `/v3/api-docs` 200 anonymously (67 operations, bearer scheme), `/swagger-ui.html` → UI renders with Authorize, 0 CSP violations; `/api/admin/users` stays 401 |
| DEP-3 | **NOT RUN on this machine** | Docker is not installed here. Verified instead: the Dockerfile's build steps on a `git archive HEAD` copy produce the jar with the example configuration; that jar, started with only `docker-compose.yml`'s environment variables against an empty PostgreSQL and the SMTP sink, migrated the schema, registered and verified a user through the sink and served an authenticated request. `docker compose up --build` must be run once where Docker exists |
| DEP-4 | **PASS after one fix** | Locally, the workflow's steps had been executed on a clean checkout with only the workflow's variables (296 tests, 57 persistence run / 0 skipped). First GitHub Actions run on the push (`3f6e23d`, run 35011208749): **failure** — `./mvnw: Permission denied`, exit 126: `mvnw` was tracked with mode `100644` from the Windows checkout, so the Linux runner could not start Maven; the gate step "Persistence tests ran, not skipped" then failed as designed (`no persistence test reports found`). Fixed in `c581b90` (`git update-index --chmod=+x mvnw`, content unchanged). Second run (run 35011449486): **success** — all steps green, `Tests run: 296, Failures: 0, Errors: 0, Skipped: 0`, `persistence tests: 57 run, 0 skipped` against the workflow's PostgreSQL 17 service, `Line coverage: 71.3% (2413 of 3384 lines); branch coverage: 14.2% (650 of 4579 branches)`, reports uploaded as the artifact |
| DEP-5 | PASS | `index.html` (200) and `/api/admin/users` (401) both carry `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security: max-age=31536000 ; includeSubDomains`, `Content-Security-Policy` |
| DEP-6 | PASS (verified at commit) | default profile: DEBUG + SQL on console and `logs/nidaa.log`; `--spring.profiles.active=prod`: 0 `Hibernate:` lines, 0 DEBUG lines, denial logged with `[request id] [user]` |
| DOC-SEC | PASS | every finding ID from S-1 to DEP-6 appears in `docs/SECURITY.md` §4; §3.7 Accountability and §5 verification added |
| PA-1 | PASS | the disable site still names both enforcement points (`SecurityConfig` lines 77–83) |
| C-4 | PASS | gate database: `activity_logs` holds 6 `USER_APPROVED` and 1 `REQUEST_STATUS_CHANGED` rows with actor and IP; the prod-profile log line `WARN [dep6-prod-check] [dep6-bene@…] Access denied …` shows the correlation id and user |

## G6 · Sign-off

**Deviations and discoveries**

1. **Invariant `unrestored_capacity_on_closed`** — same as Gate 3, still open. Every
   violating row is a COMPLETED assignment; the documented design consumes capacity on
   completion and restores it only on cancellation. Recommended: amend the query to
   `status = 'CANCELLED'`. Nothing was changed on either side pending the decision.
   *Resolved after this gate: the owner chose the amendment; the query now checks
   CANCELLED only plus its converse, both 0 on both databases. Decision record:
   `docs/adr/005-capacity-consumed-on-completion.md`.*
2. **Docker not available on the gate machine** (DEP-3 NOT RUN), recorded honestly
   above rather than marked PASS. *Owner's decision after this gate: NOT RUN is
   accepted for Gate 4, and **Gate 7 is blocked** until `docker compose up --build`
   has been run once on a machine with Docker and its outcome recorded.* **The first CI run failed** (DEP-4): `mvnw` had no
   executable bit in git. Fixed in one commit (`c581b90`), second run green — the
   workflow caught exactly the class of environment gap it exists to catch.
3. **Plan inventory gaps found and handled inside the task:** F-3 listed three images
   (3.3 MB) but `index.html` also loaded six help-type PNGs (11.3 MB) — converted with
   the same treatment; P-2 said "about 12" endpoints — it was 21.
4. **Judgement calls inside tasks:** repeating a transition another actor already made
   is 409, not 400 (B-4); colour tokens were darkened one step for WCAG contrast (F-5);
   `totalUsers` excludes anonymised accounts and the public provider counts count
   active accounts only (Q-2); a local mail sink is part of `docker-compose.yml`
   because registration cannot complete without mail (DEP-3).
5. **Recorded in `FUTURE_WORK.md`, not fixed:** `register.html` has no
   verification-code step (pre-existing; a person can complete registration only
   through the API); `psychologists.specialization` is an array column mapped as
   `TEXT`; `psychological_requests.completed_at` is never written; `/actuator/health`
   is not public; MapStruct is declared but unused; `HelpRequestService` still resolves
   volunteer/organization ids with the raw-SELECT pattern C-3 removed for psychologists.
6. **Open decision:** whether admin approval should set `psychologists.is_verified`
   (the duty toggle now tells an unverified psychologist that routing skips them).
   *Resolved after this gate: the owner chose a separate credential-verification
   action (`PUT /api/admin/psychologists/{userId}/verification`, admin-users.html)
   and approval now creates the profile off duty; see the `feat(approval)` commit.*
7. `logs/humanitarian-platform.log`, committed in the first commit, was removed from
   the repository (DEP-6).

**Security-relevant changes:** all in `docs/SECURITY.md` (§3.2 status contract with
409 and the envelope, §3.5 guarded updates, §3.7 accountability, §4 rows D-6…DEP-6).

**Commits:** 23 on `audit-remediation` for the Phase 4 tasks, one per task ID,
`Audit-Ref` in each, plus this record and the `mvnw` mode fix (`c581b90`, `Audit-Ref:
DEP-4`); the branch is pushed with this record.

**Summary.** Phase 4 hardened the code and its presentation: the schema is owned by
Flyway and agrees with the entities down to the enum types; every response has one
envelope and every status transition is a guarded UPDATE that loses races with a 409;
administrators' actions are audited and every log line carries a request id;
statistics and listings no longer load tables; the pages are keyboard-operable, pass
WCAG A/AA in axe, and run under `script-src 'self'`; the landing page dropped from
13.9 MB to 178 KB of images; Swagger, Docker, CI, security headers and rolling logs are
in place, and CI is green on GitHub. Outstanding at sign-off: the capacity-invariant
decision, the psychologist-verification decision, and running `docker compose up` on a
machine with Docker. *All three were decided after the gate (items 1, 2 and 6 above);
the Docker run is carried forward as a Gate 7 blocker.*
