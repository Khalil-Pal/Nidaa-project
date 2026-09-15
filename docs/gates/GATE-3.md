# Gate 3 — after Phase 3

Run on 14 September 2026 against commit `90e3daf` plus the fixes recorded below.
Tooling: `scripts/gate/` (fresh database, invariants, SMTP sink, API acceptance,
browser checks). Evidence files were produced by those scripts; the figures below
are copied from their output.

## Outstanding items from Master Plan §3

| Item | Result | Evidence |
|---|---|---|
| 3.1 clamp | PASS | `PriorityScoreService` caps aging at 20 and clamps to 100 (D-5, `d85c493`); `HelpRequestPersistenceTest` asserts persisted == `calculate(fromDb)` and that a 400-hour-old worst case stores exactly 100 (`90e3daf`); `priority_out_of_range = 0` on both databases; `PriorityScoreSchedulerTest.oneFailingRowDoesNotStopTheOthers` |
| 3.2 L-2 inflections | PASS | HIGH-tier stems; ten inflected forms tested as crises (`ef93cd9`); live: "self-harming" → `isCrisis=true` |
| 3.3 Gate 3 | see below | |
| 3.4 push | done at the end of this gate | |

## G1 · Build and test

| Check | Result | Evidence |
|---|---|---|
| Clean build, all tests pass | PASS | `./mvnw clean test`: 264 tests, 0 failures, 0 errors, 0 skipped (Phase 2 end: 165; Phase 3 end: 252) |
| No test disabled, skipped or deleted | PASS | `grep @Disabled\|@Ignore src/test` → none; persistence tests report `skipped="0"` |
| New tests mapped to task IDs | PASS | class-level comments name the task: `security/*` (T-1, S-1/4/5/7/8/9/15, P-1, S-2, B-2), `persistence/*` (T-2, D-2, D-5, V11, V13, V16), `HelpRequestOnBehalfTest` (ON-1), `CrisisDetectorServiceTest` (L-2), `PriorityScore*Test` (D-5), `RateLimitFilterTest` (S-9), `GlobalExceptionHandlerTest` (S-10), `nidaa-common.test.js` (F-2, F-4) |

## G2 · Fresh environment

| Check | Result | Evidence |
|---|---|---|
| Every migration applies to an empty database in order | PASS | `fresh-db.sh nidaa_gate`: V1…V16 all `PASS apply` (version order, not glob order) |
| Re-running is a no-op by exit code | PASS for V9–V16 | all `PASS rerun (exit 0)`. V1–V8 are shipped non-idempotent migrations (V4 must never be re-run on a populated database, documented in `database/migrations/README.md`); DEP-1 (Flyway) makes this moot |
| App starts against the fresh database | PASS | `Started PlatformApplication` with `DB_URL=…/nidaa_gate` |
| Smoke path register → verify → login → submit → accept → complete | PASS | `acceptance.sh`: beneficiary registered and verified with the code from `pending_registrations`; volunteer pending until admin approval, then logged in; request A submitted, accepted, completed with `completed_at` set |
| App fails loudly without required variables | PASS | started with `.env` aside and no `JWT_SECRET`: exit 1, `Could not resolve placeholder 'JWT_SECRET'`, no `Started` line |
| `dropdb nidaa_gate` | done after the run | |

**Finding fixed at this gate (G2 was red):** approving a psychologist or an
organization through the application failed on the fresh schema with 409, because
`AdminController.approveUser` inserts only the columns registration collects and
`psychologists.specialization` / `organizations.registration_number` were NOT NULL
without defaults. The approval transaction rolled back and the account stayed
inactive. V16 makes both columns nullable; `UserPersistenceTest.approvalShapedProviderRowsPersist`
inserts the exact approval-shaped rows. Re-run: psychologist approved and logged in.

## G3 · Schema and code agreement

| Check | Result | Evidence |
|---|---|---|
| Every trigger, function and view expected and documented | PASS | inventory: 1 function (`update_updated_at_column`), 6 `updated_at` triggers, 0 views; documented in `docs/DATABASE.md` |
| No trigger writes a column the app also writes, or proven harmless | PASS (proven) | the `updated_at` triggers and `@UpdateTimestamp` both write "now"; trigger fires only BEFORE UPDATE; no decision reads the column. Documented in `DATABASE.md` |
| Inventory unchanged since last gate or explained | EXPLAINED | Web_DB carried `pgcrypto` functions from a one-off hash in Phase 1; dropped, now identical to the fresh database |
| Persisted == computed for every stored computed value | PASS | `HelpRequestPersistenceTest.priorityScoreIsStoredAsTheApplicationComputedIt` |
| Every enum column has a parameterised real-database test | PASS | `EnumRoundTripPersistenceTest`: 8 categories, 20 help type × urgency pairs, support type × format |
| Invariant query set returns zero | **FAIL on one count, see decision below** | Web_DB: all 14 zero. nidaa_gate after the acceptance run: 13 zero, `unrestored_capacity_on_closed = 1` |

## G4 · Regression (Phases 1 and 2 re-verified through the API)

All PASS. From `acceptance.sh` (96 passing checks): S-1 400; D-1 six categories persist as
ANXIETY/DEPRESSION/PTSD/GRIEF/VIOLENCE/CRISIS; UX-1 200; S-6 no allow header for
`evil.example`, header present for the configured origin; S-15 401 / 403 / expired 401;
S-4 404 for another beneficiary (help and psychological), 200 owner and admin; S-5 403
unassigned volunteer, 403 beneficiary completing, 200 assigned volunteer, 200 owner
cancelling, 403 other beneficiary cancelling, 403 psychologist closing an unassigned
case; P-1 five role/URL checks; S-10 404 unknown route, 400 non-numeric id, no detail in
the body; S-8 identical login failure bodies, identical forgot-password replies; S-7 old
token 401 after reset, new password logs in; S-9 wrong reset code refused, limiter
engaged at call 53 of a burst from a second address, other address still served; ON-1
volunteer files for a new beneficiary, cannot accept or complete it, can read it,
beneficiary supplying `beneficiaryEmail` gets 400; D-2 self-delete needs the password,
row anonymised, old email 401.

## G5 · Acceptance (Phase 3, verified now)

| Task | Result | Evidence |
|---|---|---|
| L-1 | PASS | API: without coordinates PENDING, with coordinates ASSIGNED and `assignment_source = AUTO_GEO`. Browser: location granted → "Location captured … assigned automatically", submitted request ASSIGNED, toast "matched to the nearest available provider"; location denied → "Location permission was declined. No location shared — this request will be matched manually" |
| F-2 | PASS | no page defines `API`, `authHeader`, `escHtml`, `logout` or `buildSidebar`; every API page includes `nidaa-common.js` once |
| S-2 | PASS | API: payload title 400, overlong description 400, CSP with `connect-src 'self'`. Browser: a row planted directly in the database with title `<img src=x onerror=alert(1)>` renders as literal text in the admin queue, no `<img>` element, no dialog, no page errors |
| F-4 | PASS | API: refresh returns a rotated pair, old refresh token 400, logout revokes. Browser: with an expired access token the dashboard loads, exactly one refresh call (HTTP 200), token replaced, no redirect to login |
| B-2 | PASS | `helpType=GROCERIES` → 400 naming the field; zero rows stored as OTHER |
| L-2 | PASS | "urgent" → false/false; "kill myself" → true/false; "self-harming" → true/false; "hopeless" → false/true |
| D-5 | PASS | `priority_score > 100` count 0; persisted == computed test |
| T-2 | PASS | 47 persistence tests ran against nidaa_test, `skipped="0"` |

## G6 · Sign-off

**Deviations and discoveries**

1. **Invariant `unrestored_capacity_on_closed` contradicts the documented design.**
   `docs/BACKEND_GUIDE.md` ("COMPLETED means the contribution was fulfilled and keeps
   that amount consumed") and the V6 column comment ("Set once when a cancelled
   assignment returns its reserved capacity") both say capacity is restored on
   cancellation only. The plan's query includes COMPLETED, so every completed
   numeric-capacity delivery counts as a violation. Not changed on either side;
   **decision requested**: amend the invariant to `status = 'CANCELLED'`, or change the
   design so completion also releases capacity (which would return delivered goods to
   inventory). *Resolved after Gate 4: the query was amended; see
   `docs/adr/005-capacity-consumed-on-completion.md`.*
2. **Psychologist approval never marks the professional verified.** Approval sets
   `is_on_duty = true` but leaves `psychologists.is_verified = false`, and crisis
   routing requires both. On a fresh database no crisis case is ever routed unless
   someone sets the flag by SQL. Recorded in `FUTURE_WORK.md` for Phase 4 (A-2 / UX-2).
3. **SMTP auth and SSL are now environment-configurable** (`MAIL_AUTH`, `MAIL_SSL`,
   defaults unchanged) so a local sink can receive the verification emails the smoke
   path depends on. Not a task in the plan; required to run G2.
4. Gate tooling added under `scripts/gate/`; `puppeteer-core` is not a repository
   dependency (installed on demand, see `scripts/gate/README.md`).

**Security-relevant changes:** none beyond those already in `SECURITY.md`.

**Summary.** Phase 3's goals hold when checked the way a user reaches them: automatic
matching runs from the browser, the XSS payload renders as text, an expired token is
refreshed silently, invalid input is refused, crisis scoring behaves as specified. The
fresh-environment path exposed a defect that no test had covered (provider approval
failing on NOT NULL columns), fixed in V16 with a test. One invariant remains red
pending a design decision.
