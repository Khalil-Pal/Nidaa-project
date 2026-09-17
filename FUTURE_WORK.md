# Future Work

Ideas and follow-ups that surfaced during the audit remediation but were not part of
the agreed scope. Each entry says what it would do and why it was deferred, so that
"not done" reads as "planned".

## The three unwired pipelines (deliberate future scope)

After Phase 5 every other table drives a feature: notifications (N-1), reports
(R-1), consultations (CS-1), the provider counters (AGG-1), the `IN_PROGRESS`
state (W-1), on-behalf filing in the UI (ON-2) and server-backed likes and
comments (CM-1). Three pipelines remain unwired: the two the plan names, and
the `locations` gazetteer the owner decided after Gate 5 to keep as future
scope. Their tables, enums, entities and repositories exist from V1 and are
left in place on purpose; no endpoint reads or writes them, and no page
mentions them.

- **Group sessions** — `group_sessions` and `group_participants` (entities
  `GroupSession`, `GroupParticipant`, `GroupSessionRepository`). A verified
  psychologist would schedule a session on a category (`psychological_category`,
  a `consultation_format`, `max_participants`, `scheduled_at`, an optional
  recurrence), beneficiaries would join until it is full, attendance would be
  recorded and each participant could leave a rating, feeding
  `psychologists.consultation_count` the way CS-1 does for one-to-one cases. It
  is deferred because it needs a scheduling and a joining flow, capacity
  handling for seats (the pattern exists in `assignments`), and a decision on
  how anonymous beneficiaries appear to each other in a group.
- **Self-help materials and request media** — `self_help_materials`
  (`SelfHelpMaterial`, `SelfHelpMaterialRepository`; `material_type` ARTICLE,
  VIDEO, AUDIO, INSTRUCTION; `content_url`, `thumbnail_url`, `tags`, view and
  helpful counts, `is_published`) and `request_media` (`RequestMedia`,
  `RequestMediaRepository`; a file attached to a help request). Materials would
  be a library a psychologist publishes and a beneficiary browses by category,
  with a "this helped" counter; request media would let a beneficiary attach a
  photo of what they need and a volunteer attach one of what was delivered
  (`reports.photos` waits on the same thing). Both are deferred for one reason:
  file storage and serving is its own security surface — upload limits, MIME
  and content sniffing, virus scanning, private storage, signed URLs — and the
  platform has no such component; hosting only URLs to material stored elsewhere
  would be a smaller first step for the library.
- **Regional gazetteer** — `locations` (`Location`, `LocationRepository`;
  `country`, `region`, `city`, `district`, coordinates, `place_name`,
  `population_estimate`). It has a primary key and a city index and nothing
  else: no foreign key points at it and no service reads it; requests carry
  their own coordinates and address, and `RequestRegionResolver` derives a
  region from a one-degree coordinate band or the last part of the address.
  It is neither seeded nor dropped (owner decision after Gate 5): the matching
  study (EV-1) generates its own data, and `DataSeeder` already produces
  geographic clusters, so regional fairness in the evaluation comes from those
  clusters, not from a lookup table. What the table would add later is
  `population_estimate`: with real regions and their populations, fairness
  could be measured per capita (requests served per thousand inhabitants of a
  region) rather than per request, which is the natural next metric if the
  PostGIS benchmark (EV-2) ever happens and coordinates become queryable by
  region.

## Cancelled by the owner

- **Direct messaging (DM-1).** `MessageType.DIRECT`, `MessageRepository.findConversation`,
  `findByReceiverIdAndIsReadFalse` and `countByReceiverIdAndIsReadFalse` and the
  CHECK `chk_messages_receiver_by_type` exist from V1 and no controller exposes
  them. The plan scoped DM-1, if approved, to request-linked conversations
  (beneficiary ↔ assigned provider, tied to a request id, never general
  user-to-user messaging) with `isAnonymous` respected. The owner cancelled it
  after Gate 5: it is the only task that opens a new surface rather than
  completing one, it carries moderation obligations on a platform serving
  vulnerable people, and it adds nothing to a thesis about distribution
  algorithms. The repository methods and the CHECK stay as they are; a future
  implementation starts from that scope and from the community moderation
  pattern (`MessageService.deleteMessage`, `message_deletions`).

## Security

- **Strict `script-src`.** Done in Phase 4 (F-5): every page's script lives in
  `js/<page>.js`, the inline handlers are gone and `script-src` is `'self'` plus
  the Chart.js CDN. `style-src` still needs `'unsafe-inline'` for the inline
  `style="..."` attributes; moving those into classes would allow a strict
  `style-src` too.
- **Per-device refresh tokens.** Rotation is per account, so two tabs that refresh
  at once log the slower one out. A `refresh_tokens` row per device would fix it.
- **`httpOnly` cookie for the access token.** Would remove the token from script
  reach entirely, at the cost of CSRF protection and a different session model.

## Crisis detection

- **Proper lemmatisation per language.** HIGH-tier terms are prefix stems and
  Arabic stems accept the definite article, which covers the common inflections.
  Irregular forms and other Arabic prefixes (و, ب, ل) are not handled; a
  language-aware pass would.
- **Negation handling.** "I am not suicidal" scores as a crisis today, on purpose.
  A negation window could lower it to the review band rather than dismiss it.

## Data model

- **Volunteer and psychologist rating default.** Done in Phase 4 (D-3, V18):
  the entities default `rating` to `null` and the CHECK permits NULL.
- **Stories on the server.** Success stories are stored in the browser's
  `localStorage`, so they are per browser and moderation is local too. The
  community feed already has the server-side pattern (`MessageService`) to copy.

- **`psychologists.specialization` is an array in the database, a string in the
  entity.** The column is `psychological_category[]`; `Psychologist.specialization`
  is mapped as `TEXT`. Only NULL round-trips today (V16 made the column nullable,
  approval leaves it NULL). Any screen that lets a psychologist record
  specialisations needs the entity changed to a list of the enum first. Found during A-2.

- **A lifecycle CHECK on `assignments`.** ADR 005 fixes that only CANCELLED
  restores capacity. The database does not enforce it: a constraint
  `(status <> 'COMPLETED' OR capacity_restored_at IS NULL)` would. The four CHECKs
  on `assignments` today are about row shape, not lifecycle, so this is a schema
  decision for Phase 5's assignment work rather than a follow-up to the gate query.

## Consultation records (Phase 5, CS-1)

- **`consultations.chat_session_id`** exists and is never written: the platform
  has no chat system for a session id to reference. The column stays for the
  day one exists.
- **Per-session records** were the Gate 5 judgement call the owner overturned:
  V22 dropped the one-per-case constraint, the endpoints became
  `.../{id}/consultations` and `.../consultations/{consultationId}/feedback`,
  and a case stays `ASSIGNED` until the psychologist completes it. Nothing is
  left to do here.

## Completion reports (Phase 5, R-1)

- **Organization completion reports.** `reports.volunteer_id` is `NOT NULL` with a
  foreign key to `volunteers`, so a completed assignment delivered by an
  organization has no report row to write; the endpoint answers 400 with a plain
  message. Supporting it needs `volunteer_id` nullable, an `organization_id`
  column, a CHECK that exactly one is set (the `assignments` pattern from V7), and
  the AGG-1 counters for organizations.
- **Report photos.** `reports.photos` exists and is never written: file upload is
  its own security surface (type sniffing, size limits, storage, serving) and no
  endpoint accepts one.

## Operations

- **Public `/actuator/health`.** The deny-by-default chain answers it 401, so a
  Docker or orchestrator health check cannot use it yet; permitting just
  `/actuator/health` (not `/actuator/**`) would allow `docker compose` and CI
  readiness probes. Found while checking DEP-3's compose contract.
- **Shared rate-limit store** if the application is ever run on more than one node.
- **Flyway** for the migrations: done in Phase 4 (DEP-1). Existing databases are
  baselined at V18 on first start; `database/migrations/README.md` has the rules.
