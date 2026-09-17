# Future Work

Ideas and follow-ups that surfaced during the audit remediation but were not part of
the agreed scope. Each entry says what it would do and why it was deferred, so that
"not done" reads as "planned". The two unwired pipelines named in the roadmap
(group sessions, self-help materials and request media) are added here after
Phase 5.

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
- **One record per case.** V20 makes `psychological_request_id` unique, matching
  the endpoints (`.../{id}/consultation` and the feedback under it). A case that
  needs several sessions records them as one consultation today; if
  per-session records are ever wanted, the constraint goes and the feedback
  endpoint needs a consultation id.

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
