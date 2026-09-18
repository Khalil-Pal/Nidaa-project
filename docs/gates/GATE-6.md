# Gate 6 — after Phase 6

Run on 18 September 2026 against commit `671ea47` (Phase 6: EV-1, UT-1, PF-1, plus
the two gaps a supervisor review added to the phase — GAP-1 and GAP-2 — and the
scoring justification they asked for). Tooling: `scripts/gate/` — `fresh-db.sh`
(Flyway), `invariants.sql`, `smtp-sink.py`, `acceptance.sh` (238 API checks),
`browser-checks.js` (78), `a11y-audit.js`, `keyboard-checks.js`, `lighthouse.js`;
`scripts/perf/` (PF-1) and `scripts/ut/` (UT-1) are new this phase. Figures are
copied from their output in the order SESSION_HANDOFF.md §2 prescribes: database
rebuilt, jar built, app started against it, acceptance on the fresh database,
browser fixtures, browser checks.

**One item is NOT RUN and cannot be run here: UT-1's sessions need people.** The
protocol, the task scripts, the observation sheet, the SUS instrument and the
session setup script are complete and verified; no session has been held, so no
usability finding or SUS score exists. See G5 and G6.

The Docker run stays carried forward from Gate 4 as a Gate 7 blocker and was not
attempted here. DM-1 was cancelled by the owner after Gate 5 and is recorded in
`FUTURE_WORK.md`.

## G1 · Build and test

| Check | Result | Evidence |
|---|---|---|
| Clean build, all tests pass | PASS | `./mvnw clean test`: **400 tests, 0 failures, 0 errors, 0 skipped** (Gate 5: 365). `node --test src/test/js/nidaa-common.test.js`: 1/1 |
| No test disabled, skipped or deleted | PASS | `grep -rn "@Disabled\|@Ignore" src/test` → 0; every surefire report `skipped="0"` (58 of 58); persistence tests **83** run, 0 skipped; `git diff --name-status b89e7ab..HEAD -- src/test` shows only A (7) and M (5), no deletions |
| New tests mapped to task IDs | PASS | EV-1: `SyntheticDataGeneratorTest` (3), `MatchingSimulationTest` (7), `MatchingStudyTest` (3), `GeoNearestStrategyTest` (2), `PriorityScoreServiceTest.agingFollowsTheInjectedClock`; GAP-1: `RequestDeclineSecurityTest` (9), `DeclineAndAttentionPersistenceTest` (4), `AutomaticAssignmentServiceTest` +2; GAP-2: `StaleRequestSchedulerTest` (4). PF-1 and UT-1 add tooling, not application code, and are verified by running them (G5) |

## G2 · Fresh environment

| Check | Result | Evidence |
|---|---|---|
| Database builds from the migration files alone | PASS | `scripts/gate/fresh-db.sh nidaa_gate`: `flyway:migrate` on an empty database, history **1–11, 13–23** exactly matching the files (V12 was never issued), no failed rows |
| Migrations are idempotent | PASS | V22 and V23 re-applied by hand with `psql -f` on the database that already had them: 0 errors, NOTICE lines only |
| Migrations also work on a baselined database | PASS | `nidaa_test` and `Web_DB` both show V22 and V23 on top of their `18:BASELINE` history, applied by the application and by the test slice's Flyway rather than by a gate script — Flyway working as intended, on databases with accumulated data |
| The application refuses to start without its secrets | PASS | `.env` moved aside, `JWT_SECRET` unset, port 8090: **exit code 1**, 0 "Started PlatformApplication" lines, `Could not resolve placeholder 'JWT_SECRET' in value` in the log. `.env` restored by the script's trap |

## G3 · Schema and code agreement

| Check | Result | Evidence |
|---|---|---|
| Entities match the schema | PASS | `SchemaAgreementPersistenceTest` and `EnumRoundTripPersistenceTest` green on the V23 database; the two new columns (`help_requests.needs_attention*`) round-trip in `DeclineAndAttentionPersistenceTest` |
| Trigger / function / view inventory unchanged | PASS | re-run on the fresh V23 database: one function `update_updated_at_column` and six `update_*_updated_at` BEFORE UPDATE triggers, no views — identical to Gates 3, 4 and 5. The phase added three columns, four indexes and no trigger |
| Invariant query set returns zero | PASS | **17 checks** (15 from the plan's appendix plus two this phase added for GAP-1: `unrestored_capacity_on_declined`, `declined_request_still_assigned`), all 0 on `nidaa_gate` after the full run |

## G4 · Regression (Phases 1–5 re-verified)

| Check | Result | Evidence |
|---|---|---|
| Every earlier acceptance check still passes | PASS | `acceptance.sh`: **238 passed, 0 failed** (Gate 5: 211; +19 GAP checks, +8 from the CS-1 rework). Nothing was removed |
| Every earlier browser check still passes | PASS | `browser-checks.js`: **78 passed, 0 failed** (Gate 5: 71; +5 GAP-1, +2 from the CS-1 rework) |
| Accessibility unchanged | PASS | `a11y-audit.js gate6`: 18 pages, **0 axe violation nodes, 0 CSP violations, 0 inline handlers/scripts/js-hrefs**. Ad-hoc audits this phase: the admin queue with a flagged row rendered (0), the sessions modal in five states (0) |
| Keyboard operation unchanged | PASS | `keyboard-checks.js`: **19 passed, 0 failed** |
| Page performance not regressed | PASS | Lighthouse `index.html`: mobile **90**, LCP 2.9 s; desktop **82**, LCP 2.0 s; images 142 KB mobile / 183 KB desktop. Gate 5: mobile 90 / 2.0 s, desktop 80 / 2.1 s — within the run-to-run spread of this machine, and the image budget is unchanged |

## G5 · Acceptance (Phase 6, verified now)

| Task | Result | Evidence |
|---|---|---|
| EV-1 — every dataset generated after V15 | PASS | No dataset touches the database: `SyntheticDataGenerator` builds requests in memory and every priority score comes from `PriorityScoreService`, the post-V15 Java model. No trigger-computed score exists anywhere in the study |
| EV-1 — at least 10 seeded repetitions per cell, with standard deviations | PASS | 4 strategies × 3 loads × 2 densities × **10 repetitions = 240 runs**; `results.md`/`.csv` report mean ± sample sd for every cell; `runs.csv` carries all 240. The design is paired: one dataset per (load, density, repetition), given to all four strategies |
| EV-1 — the findings show a trade-off, not one strategy winning everything | PASS | At HIGH/SPARSE the multi-objective strategy reaches CRITICAL requests in **3.1 h** against geo-nearest's 44.9 h (10/10 paired datasets), while geo-nearest completes **7.1 points more** of everything (10/10) on 2.9 km less travel. Under dense provision nearest-first buys its distance saving with a utilisation Gini of 0.37 against 0.09. Figure 5 shows no strategy in the lower-left corner |
| EV-1 — threats to validity named explicitly | PASS | §5 of the chapter: synthetic data, no real travel times, the decline rate as an assumption, no provider drop-out or resource filter, no time-of-day effects, the ranking/selection confound (with the 2 × 2 design named as the fix), one geometry, the choice of windows and indices, the drain, sample size, no production traffic |
| EV-1 — results reproducible: the same seed gives the same numbers | PASS | A second run of the documented command into a scratch directory: **checksum `9c0f4b2f6e68ef7d` both times**, and `runs.csv`, `results.csv`, `results.md`, `sensitivity.csv`, `sensitivity.md` and the figures **identical byte for byte** (`cmp`). `MatchingStudyTest` asserts the same property in the build |
| EV-1 — sensitivity analysis (owner's addition) | PASS | The same datasets re-run under five weight variants (production, levelled vulnerability, doubled urgency, uncapped waiting, no vulnerability): every headline conclusion survives every variant; `sensitivity.md` and `.csv` carry the comparison, and the chapter says what it does and does not settle |
| EV-1 — measured against the corrected system (owner's requirement) | PASS | The simulation models arrival-time matching, the 30-minute retry sweep (GAP-2), provider declines and escalation (GAP-1); each mechanism is mapped to the class that implements it in §2.1 of the chapter. The pre-GAP results were discarded and the study re-run |
| UT-1 — scripts, observations and SUS recorded; top three findings fixed and re-tested | **NOT RUN** | The materials are complete and verified: protocol, three five-task role scripts, observation sheet, the ten standard SUS items with scoring and a worked example, and the results template — whose first line says **NOT YET RUN**. `scripts/ut/setup-participants.sh` was executed end to end against a throwaway `nidaa_ut` database: 7 accounts, 8 pending requests across the three settlements, 1 waiting psychological case, 1 assigned anonymous case, psychologist verified and **off duty**; verified through the API as each role, then the database was dropped. **Sessions need five to ten people and cannot be held from here.** |
| PF-1 — p50/p95 recorded at all three concurrency levels | PASS | Four endpoints × 1/10/50 concurrent users against **10,000 seeded requests**, 10 s warm-up discarded, 15 s measured, 0 errors: login 63.7/75.7/357.8 ms p50 (p95 78.5/85.6/376.8), ranked queue 15.6/9.0/40.9 (18.1/14.0/60.7), admin stats 15.7/14.3/74.0 (20.9/18.3/104.0), submission 15.5/5.1/19.8 (17.4/8.2/36.1). Raw JSON in `docs/evaluation/pf-1/` |
| PF-1 — anything optimised was re-measured | PASS, nothing optimised | At the specified scale no endpoint was slow: the slowest is login at 63.7 ms, which is bcrypt at 10 rounds — a security control, not a defect. The scaling experiment on the 121,495-request database the run leaves behind identifies `GET /api/admin/stats` as the one endpoint that grows with the row count (15.7 → 79.9 ms at one user); recorded in `FUTURE_WORK.md` with the incremental-counter fix rather than optimised, because optimising a 16 ms endpoint would be optimising the wrong thing. **The harness itself was fixed and re-measured**: the first run put the write scenario first, so the reads afterwards measured an inflated database |
| GAP-1 — decline restores capacity exactly; the same provider is never re-offered; a declined request that finds nobody stays PENDING | PASS | API (19 checks): the decline leaves the request `PENDING` with no provider and `cancelled_at` still null, the assignment `DECLINED` with the reason and a closing time, capacity restored on every declined row, the beneficiary told, the decliner not the one it went to, the same provider cannot decline twice (404), the third decline **escalates** (`needs_attention`, the reason, every administrator notified) instead of looping, and assignment clears the flag. Browser (5): the card offers "I can't do this one", the card leaves the board, the database shows PENDING/unassigned/not cancelled/one decline, and the beneficiary still sees their request waiting. Unit: a decliner is skipped for the next nearest; a request everyone declined stays PENDING and reserves nothing. Persistence: the guarded restore fires once and a second attempt changes nothing |
| GAP-2 — an urgent stale request is retried before a less urgent one; retry respects the decline exclusions | PASS | `StaleRequestSchedulerTest`: the sweep considers requests in priority order (asserted on the order offered, not just the query), passes the decline exclusions through, flags only what is past the escalation age and does so once, and one failing request does not stop the sweep. `DeclineAndAttentionPersistenceTest` proves the query against the real schema (unassigned PENDING only, highest score first). API: a request nobody can take stays PENDING, unassigned and unflagged before the escalation age. **A 30-minute schedule cannot be exercised inside a gate run**, which the acceptance script states in a comment |
| The priority weights are defended (owner's addition) | PASS | `docs/SCORING.md`: what the score is for and where it acts (the GAP-2 sweep and the ranked queue, not the match on arrival), each weight with its reasoning — including why a disability outweighs children, as an argument about substitutability of help — the non-goals, and five named limitations. Every arithmetic example checked against `PriorityScoreService`. Linked from the README's priority table |

## G6 · Sign-off

**Deviations and discoveries**

1. **The phase grew by three items** on the owner's instruction after a supervisor
   review: GAP-1 (provider decline and reassignment), GAP-2 (retry and escalate
   stale requests) and the scoring justification. Both gaps were built before the
   experimental runs, so EV-1 measures the corrected assignment flow.
2. **EV-1's first results were discarded.** The study had been run and committed
   (`be6ad0b`) against the pre-GAP system; the simulation was rebuilt around the
   real mechanisms and everything re-run (`c046433`). The conclusions kept their
   shape and changed in size — the multi-objective strategy's advantage on
   CRITICAL waiting at HIGH/SPARSE grew from 25 h to 42 h against geo-nearest.
3. **A modelling decision worth naming:** the simulation now shows the cost of the
   30-minute sweep, because a provider who becomes free waits for the next tick
   exactly as they do in the platform. At LOW/DENSE that interval is most of the
   residual waiting time. Halving it is the cheapest available improvement and is
   not made here.
4. **Judgement calls inside the gaps** (mine, stated in the commits): a decline is
   `DECLINED` rather than `CANCELLED` so the row records who refused and the
   rematch can exclude them; non-parties get 404 rather than 403 on the decline
   endpoint, the rule the request services already follow; the third decline
   escalates rather than rematching; the attention flag is raised once by a
   guarded UPDATE and cleared by assignment, automatic or manual; the sweep is not
   `@Transactional` so one failing request isolates.
5. **Pre-existing defect found by the new work:** the load-test harness measured a
   moving target (the write scenario inflating the dataset for the reads that
   followed). Found and fixed inside PF-1, and the corrected numbers are the ones
   reported.
6. **Recorded in `FUTURE_WORK.md`:** the 2 × 2 design that would separate the
   ordering rule from the provider-selection rule, replaying real requests once
   there are any, vulnerability weights from field practice, and the incremental
   admin counters PF-1's scaling curve points at.
7. **Carried forward, unchanged:** Docker (`docker compose up --build`) has still
   not been run on any machine — Gate 7 blocker per the owner's decision after
   Gate 4.

**Security-relevant changes:** `docs/SECURITY.md` §3.2 gained the decline rule
(only the request's own assigned provider, only while ASSIGNED, 404 to everyone
else, and a decline never cancels the beneficiary's request).

**Commits:** on `audit-remediation` since Gate 5: `4e257d0` CS-1 (per-session
records, V22), `bc875d0` W-1 (justification), `fcd33bf` ON-2 (identity principle),
`c5e95b3` DOC-FW (`locations`, DM-1 cancelled), `b89e7ab` (Gate 5 decisions),
`be6ad0b` EV-1 (superseded), `fc5ebfe` UT-1, `3760fef` GAP-1, `fe8f154` GAP-2,
`4a90354` DOC-SCORING, `8490dda` PF-1 (harness), `c046433` EV-1 (corrected),
`671ea47` PF-1 (measurements) — one per task ID with `Audit-Ref`, all pushed, CI
green on every push.

**Summary.** Phase 6 turned the platform's numbers into evidence and, on the way,
closed two holes a supervisor found in the thing being evidenced: a provider who
could not say no without destroying a beneficiary's request, and a request that
nobody took and nobody was told about. The matching study now measures the
corrected system, reports a genuine trade-off rather than a winner, survives
every plausible change to the weights it depends on, and reproduces byte for byte
from a seed. The load measurement says the platform is bcrypt-bound where it
should be and flat everywhere else at the scale the plan specifies. User testing
is ready to run and has not been run, which is the one thing this gate cannot
close from a keyboard.

400 tests, 238 API checks, 78 browser checks, 18 pages at 0 accessibility
violations, 19 keyboard checks, 17 invariants at zero. Outstanding at sign-off:
the UT-1 sessions, the Docker run (Gate 7), and EV-2 — still optional, still
requiring the owner's word before it is started.
