# Gate 5 — after Phase 5

Run on 17 September 2026 against commit `c548528` (Phase 5: N-1, R-1, CS-1, AGG-1,
W-1, ON-2, CM-1, DOC-FW, one commit per task ID, plus one gate-check fix). Tooling:
`scripts/gate/` — `fresh-db.sh` (Flyway), `invariants.sql`, `smtp-sink.py`,
`acceptance.sh` (211 API checks), `browser-checks.js` (71), `a11y-audit.js`,
`keyboard-checks.js`, `lighthouse.js`. Figures below are copied from their output in
the order SESSION_HANDOFF.md §2 prescribes: database rebuilt, jar built, app started
against it, acceptance on the fresh database, browser fixtures, browser checks. DM-1
was not started (on hold by the owner; the plan says ask first). The Docker run stays
carried forward from Gate 4 as a Gate 7 blocker and was not attempted here.

## G1 · Build and test

| Check | Result | Evidence |
|---|---|---|
| Clean build, all tests pass | PASS | `./mvnw clean test`: **365 tests, 0 failures, 0 errors, 0 skipped** (Gate 4: 296; 323 at the start of this session). `node --test src/test/js/nidaa-common.test.js`: 1/1 |
| No test disabled, skipped or deleted | PASS | `grep -rn "@Disabled\|@Ignore" src/test` → 0; every surefire report `skipped="0"`; persistence tests 79 run, 0 skipped; no test class removed since Gate 4 (`git diff --name-status b2d90ce..HEAD -- src/test` shows only A and M) |
| New tests mapped to task IDs | PASS | N-1: `NotificationSecurityTest`, `NotificationPersistenceTest`, `nidaa-common.test.js` (bell); R-1: `AssignmentReportSecurityTest` (13), `ReportPersistenceTest` (2); CS-1: `ConsultationSecurityTest` (13), `ConsultationPersistenceTest` (7), `PsychologicalRequestSecurityTest` (completion query), `SchemaAgreementPersistenceTest` (`text[]`); AGG-1: `ProviderStatsPersistenceTest` (5) and the refresh verifications in the two slice tests; W-1: `SharedHelpersTest.inProgressIsReachableOnlyFromAssignedAndOnlyClosesForward`, `HelpRequestSecurityTest` +3; ON-2: `HelpRequestOnBehalfTest` +1, `HelpRequestSecurityTest.beneficiaryNameOnAFiledRequestReachesTheFilerAndAdminButNotABrowsingProvider`; CM-1: `CommunityEngagementSecurityTest` (8), `CommunityEngagementPersistenceTest` (3) |

## G2 · Fresh environment

| Check | Result | Evidence |
|---|---|---|
| Every migration applies to an empty database in order | PASS | `fresh-db.sh nidaa_gate`: `flyway:migrate` applied V1…V21 (20 files); `flyway_schema_history` lists exactly `1 2 3 4 5 6 7 8 9 10 11 13 14 15 16 17 18 19 20 21`, no failed rows. V20 and V21 are the migrations of this phase (CS-1, CM-1) |
| Re-running is a no-op by exit code | PASS | second `flyway:migrate`: exit 0; `flyway:info` shows every version `Success`, nothing pending |
| Migrations also work on a baselined database | PASS | `nidaa_test` (`18:BASELINE, 19, 20, 21`) received V20 and V21 through the persistence tests' Flyway; `Web_DB`, the owner's dev database, shows `18:BASELINE, 19:SQL, 20:SQL, 21:SQL` (the app was started against it during the phase, not by the gate). Each new file was also re-applied by hand with `psql -f` on a database that already had it: exit 0, NOTICE lines only |
| App starts against the fresh database | PASS | `Started PlatformApplication in 11.0 seconds` with `DB_URL=…/nidaa_gate`, `Schema "public" is up to date` |
| Smoke path register → verify → login → submit → accept → complete | PASS | `acceptance.sh` G2 block: beneficiary registered, code read from `pending_registrations`, verified; volunteer pending until admin approval, then logged in; request A submitted, accepted, completed with `completed_at`; psychologist approved and logged in |
| App fails loudly without required variables | PASS | `.env` moved out of the repository, `JWT_SECRET` unset, `SERVER_PORT=8090`: exit code 1, 0 `Started` lines, `Could not resolve placeholder 'JWT_SECRET'`; `.env` restored, same three keys |
| `dropdb nidaa_gate` | the database is rebuilt from V1 by `fresh-db.sh` at the start of every run; the fixtures of this run are still in it | |

## G3 · Schema and code agreement

| Check | Result | Evidence |
|---|---|---|
| Every trigger, function and view expected and documented | PASS | inventory on the fresh `nidaa_gate`: 1 function (`update_updated_at_column`), 6 `update_*_updated_at` BEFORE UPDATE triggers (`users`, `help_requests`, `psychological_requests`, `profiles`, `psychologists`, `self_help_materials`), 0 views — **identical to Gate 4**. This phase added tables (`message_reactions`, `message_comments`, V21), columns and constraints, and no trigger, function or view; documented in `docs/DATABASE.md` and `database/migrations/README.md` |
| No trigger writes a column the app also writes, or proven harmless | PASS | as at Gate 3 (`updated_at` only, BEFORE UPDATE, no decision reads it) |
| Inventory unchanged since last gate or explained | PASS | unchanged |
| Persisted == computed for every stored computed value | PASS | `HelpRequestPersistenceTest.priorityScoreIsStoredAsTheApplicationComputedIt` (priority); new this phase: `ProviderStatsPersistenceTest` — `volunteers.total_completed_requests`/`rating` and `psychologists.consultation_count`/`rating` equal `COUNT`/rounded `AVG` over reports and consultations (5, 4, 3 → 4.00 and 3; a corrected rating gives the new true mean); `docs/DATABASE.md` "Computed-and-stored values" lists them |
| Every enum column has a parameterised real-database test | PASS | `EnumRoundTripPersistenceTest` (categories, help type × urgency, support type × format), `UserPersistenceTest` (`users.role`); new: `ConsultationPersistenceTest.everyFormatRoundTripsWithTheTopicsArrayAndTheAssignmentLink` for `consultations.format` (`consultation_format`, CHAT/AUDIO/VIDEO), which the entity had mapped as `varchar(50)` (the D-4 drift named in the handoff, fixed in CS-1) |
| Every `columnDefinition` names an existing type | PASS | `SchemaAgreementPersistenceTest` (now also accepts `text[]`, used by `Consultation.topicsDiscussed`) |
| Invariant query set returns zero | PASS | `nidaa_gate` after the full run: 15 checks, all 0; `Web_DB`: 15 checks, all 0 |

## G4 · Regression (Phases 1–4 re-verified)

| Check | Result | Evidence |
|---|---|---|
| API acceptance from Gates 1–4 | PASS | `acceptance.sh`: **211 passed, 0 failed**. Every earlier criterion holds: S-1, D-1, UX-1, S-6, S-15, S-4, S-5, P-1, S-10, S-8, S-7, S-9, ON-1, D-2, L-1, F-2, S-2, F-4, B-2, VER, L-2, D-5, T-2 and the 15 invariants. One check was made deterministic (`2d9047d`): S-9 "Retry-After present" read the header off a separate probe after the burst, which once landed on a refilled token; it now reads it off the burst's own first 429 (call #54 this run) |
| Browser checks from Gates 3–4 | PASS | `browser-checks.js`: S-2 4/4, L-1 4/4, F-4 4/4, REG 11/11, VER 7/7 |
| Accessibility and keyboard | PASS | `a11y-audit.js`: 18 pages × roles, **0 axe WCAG 2.1 A/AA violations, 0 CSP violations, 0 page errors, 0 inline handlers/scripts/`javascript:`** — with a HIGH request, organization pills, a liked post, filed-on-behalf badges and an IN PROGRESS chip on the audited pages; during the phase the new modals and forms were audited open as well (consultation modal in four states, request form with the toggle on and off): 0. `keyboard-checks.js`: 19/19 |
| Lighthouse (F-3) | PASS, one number noted | images 138 KB (target < 150 KB), same six WebP files as Gate 4. Two runs on `index.html`: mobile 88 / LCP 3.1 s (machine still busy from the browser suite), then mobile 90 / LCP 2.0 s; desktop 81 / 2.1 s and 80 / 2.1 s. Gate 4 recorded mobile 98 / LCP 2.0 s, desktop 82 / 2.1 s. LCP and bytes are unchanged; the mobile score moved and is left for PF-1 (Phase 6) to measure properly rather than tuned here |
| Schema changes do not invalidate earlier assumptions | PASS | V19–V21 add constraints, columns and tables only; every earlier acceptance check and the invariants pass on the V21 schema; the G3 inventory is unchanged |

## G5 · Acceptance (Phase 5, verified now)

| Task | Result | Evidence |
|---|---|---|
| N-1 | PASS | browser: a volunteer accepting through the API raised the beneficiary's bell from 2 to 3 **after 60 s without a reload** (`aria-label "Notifications, 3 unread"`); the panel lists "Your request was accepted" unread, clicking it marks it read on the server and goes to the request; the volunteer marking the beneficiary's notification read → **404**. API: 12 N-1 checks (both parties notified on accept and complete, the actor never, other user 404 on read and absent from the list, unread count, `read_at`/status) |
| R-1 | PASS | API: exactly one report per completed assignment — the second attempt **409**, `count(*) = 1`; the beneficiary rates once (4/5) and a second rating is **409**; the beneficiary filing the provider's report → 403; an unassigned volunteer → 404; rating before the report → 400; out-of-range → 400; admin reads it, another beneficiary → 404; no photo endpoint (404). Browser: "Record what you delivered" and "Rate this help" prompts both work, 5/5 stored |
| CS-1 | PASS | API (27 checks): `notes_for_psychologist` is returned to the assigned psychologist and **the key is absent** from the beneficiary's and the administrator's responses (tested directly, `grep -c 'notesForPsychologist\|PRIVATE-NOTE' = 0`); the record carries no beneficiary id or name; the beneficiary of an anonymous case rates it (4/5) and the psychologist's notification names nobody (`content not like '%Gate beneficiary%'`); the contact endpoint still answers `anonymous: true`; one record per case (second → 409, row count 1, format stored in the enum column, topics as `text[]`, assignment linked); record before completion 400; completing a case now stamps `completed_at`. Browser (9): the psychologist records through the modal (format preselected), sees their private note; the beneficiary sees the recommendations and never the note, rates 5/5 |
| AGG-1 | PASS | API: the volunteer with reports rated 4, 5, 3 has **`rating = 4.00` and `total_completed_requests = 3`**, and the stored mean equals `round(avg(beneficiary_rating), 2)` over the reports; the volunteer with no reports has **`NULL`** and 0; the psychologist has `1/NULL` after the record and `1/4.00` after the rating. Test: 5, 4, 3 → 4.00/3; 5, 4, 4 → 4.33 then the 4 corrected to 2 → 3.67 (recomputed, not nudged). Browser: the two UI ratings (5) landed in both counters |
| W-1 | PASS | API: `PENDING → ASSIGNED → IN_PROGRESS → COMPLETED` end to end (`completed_at` set, the assignment closed); **a beneficiary attempting `IN_PROGRESS` gets 403**, an unassigned volunteer 403, `IN_PROGRESS` straight from `PENDING` 400, repeating it 409; the beneficiary was notified ("Request in progress"); the assignment row stays `ASSIGNED` while the request is in progress. Browser: "On my way" on the volunteer's card, the IN PROGRESS chip on both sides, the button gone afterwards |
| ON-2 | PASS | browser: the volunteer gets the "+" button, the form shows the toggle (on) with the beneficiary fields and the consent copy, toggling hides and reveals them; filing through the form creates a new unverified beneficiary with the typed name and e-mail, `filed_by_user_id` = the volunteer; the card shows **"Filed on behalf of Nadia Browser"** and no "Start Working"; **the filer cannot accept their own filed request (400)**; the admin queue shows the badge with the name. API: the name reaches the filer and the admin, another provider browsing sees `filedByUserId` and no name, a self-filed request has no name field |
| CM-1 | PASS | browser: a like made in one browser session shows as **"1 likes" in a second browser context signed in as another person**, who is not marked as having liked; their like and comment make it "2 likes / 1 comments" there and, after a reload, in the first session, comment by "Fixture Admin"; database 2 reactions, 1 comment. API (22): like idempotent (twice → 1), two people → 2, exactly one row per (post, user), the feed carries `likeCount`/`likedByMe` per viewer, unlike → 1, 404 on a missing post, beneficiary 403, blank and 1001-character comments 400, the thread with author names, the count follows, volunteer cannot remove, admin needs a reason, removal soft-deletes with an audit row carrying `comment_id` and notifies the author with the reason, thread and count drop it, the moderation history lists it as a comment. **`grep -c "nidaa_community_engagement\|readEngagementStore\|saveEngagement\|removeEngagement" community.js = 0`**: no `localStorage` engagement code remains, no fallback |
| Exactly two pipelines remain unwired, both in `FUTURE_WORK.md` | PASS, one discovery | `FUTURE_WORK.md` opens with the two: group sessions (`group_sessions`, `group_participants`) and self-help materials with request media (`self_help_materials`, `request_media`), each with what it would do and why it was deferred. Every other table drives a feature. **Discovery:** `locations` is a gazetteer table with no foreign key and no reader (`LocationRepository` has no caller) — not a pipeline, but a table nothing drives; recorded under "Data model" with the two options for the owner (seed it for real regions in EV-1's fairness metric, or drop it) |
| Re-run the G3 trigger inventory | PASS | re-run on the fresh V21 database at the top of this run: unchanged (see G3). The phase added two tables and no trigger |

## G6 · Sign-off

**Deviations and discoveries**

1. **Judgement calls inside tasks**, each stated in its commit and recorded in the
   code or docs, none an owner decision:
   - CS-1: one consultation record per case (`UNIQUE (psychological_request_id)`,
     V20) because the endpoints are addressed by request id; a `GET` exists; the
     administrator reads the record minus the private note (key omitted, never
     null); no consultation response or notification carries a beneficiary id or
     name; `chat_session_id` stays unused.
   - W-1: `IN_PROGRESS` for help requests only. The shared `RequestTransitions`
     allows it, but the psychologist and admin targets on psychological cases
     deliberately leave it out, for a reason in the domain rather than in the
     code: material aid has a delivery journey worth reporting ("on the way");
     psychological support does not — the span between assignment and
     completion *is* the support, so there is no intermediate state to report.
     The plan's "both transition maps" are, since C-3, the one shared map and the
     two page-side copies, all three extended. The assignment row stays
     `ASSIGNED` while the request is in progress (capacity and availability
     follow the assignment). Kept as built by the owner's decision after the gate.
   - ON-2: the "Filed on behalf of <name>" badge gets the name only for viewers
     who could already learn it (the beneficiary, the filer, the assigned
     provider, an admin — the `canView` rule); a provider browsing the open list
     sees that a request was filed for someone, not for whom. The filer's own card
     offers the responder's contact and none of the deliverer's controls.
   - CM-1: like and unlike are idempotent and answer with the post's state; the
     (post, user) pair is the primary key (the UNIQUE the plan asks for); a
     removed comment gets a `message_deletions` row with `comment_id`; the shared
     rules moved to `CommunityRules` (C-3). The thread and like counts update in
     place, not by rebuilding the feed — a full rebuild wiped what a person was
     typing when comments arrived, found by the browser check.
   - AGG-1: the count is the number of reports or consultation records (the plan's
     definition), so a completed assignment without a report is not counted; the
     mean is rounded to hundredths in Java so persisted equals computed.
2. **Contradictions with the plan, recorded rather than improvised around** (from
   earlier in the phase): `reports.volunteer_id` is NOT NULL, so completion
   reports are volunteer-only and an organization's completed assignment answers
   400 (`FUTURE_WORK.md`); a rejected application has no user row left, so
   rejection is e-mail only (N-1).
3. **Pre-existing defects found by the new checks and fixed inside the tasks:**
   `psychological_requests.completed_at` was never written (CS-1);
   `Consultation.format` was mapped as `varchar(50)` on an enum column and
   `topics_discussed` (`text[]`) as a `String` (CS-1); the community page's liked
   button was `#ef4444` on `#fef2f2` (3.2:1) and had never been rendered on an
   audited page (CM-1); the S-9 gate check had a timing window (`2d9047d`).
4. **Discovery, not fixed:** the unused `locations` table (G5 above); the
   psychological page's status label still says "Assigned (In Progress)" for a
   state that cases do not use (cosmetic, left alone).
5. **Recorded in `FUTURE_WORK.md`:** the two unwired pipelines, `locations`,
   `chat_session_id`, one-record-per-case, organization reports, report photos.
6. **Carried forward, unchanged:** Docker (`docker compose up --build`) has still not
   been run on any machine — Gate 7 blocker per the owner's decision after Gate 4.
   DM-1 not started (on hold).

**Security-relevant changes:** all in `docs/SECURITY.md` — §3.2 rows for notifications
(N-1), consultation records (CS-1), status transitions with `IN_PROGRESS` (W-1),
community engagement (CM-1); §3.4 row for the on-behalf name scoping (ON-2).

**Commits:** on `audit-remediation` since Gate 4: `a68121c` (docs), `8d7a9f9` N-1,
`dc4d243` R-1, `227c386` (handoff), `c2d5e69` CS-1, `2d9047d` (S-9 gate check),
`6c571c5` AGG-1, `1e5474a` W-1, `cf190ba` ON-2, `4131fa7` CM-1, `c548528` DOC-FW —
one per task ID with `Audit-Ref`, all pushed, CI green on every push (persistence
tests run, not skipped, on a database built from V1 on Linux).

**Summary.** Phase 5 completed the pipelines: every table now drives a feature except
the two named as future scope. A beneficiary is told in-app when something happens to
their request; what a volunteer delivered and what a psychologist recorded are on
record and rated, and those ratings make the provider counters real; a provider can
say "on my way"; a volunteer can file for someone who cannot through the form, and
everyone who is entitled to know sees for whom; likes and comments are the same in
every browser and a moderator can remove a comment with an audited reason. The
schema grew by one link, one uniqueness rule, two tables and one audit column, and no
trigger. 365 tests, 211 API checks, 71 browser checks, 18 pages at 0 accessibility
violations, 15 invariants at zero on both databases. Outstanding at sign-off: the
Docker run (Gate 7), DM-1 (on hold), and the owner's call on `locations`.
