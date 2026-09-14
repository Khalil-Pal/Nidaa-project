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

- **`psychological_requests.completed_at` is never written.** Completing a
  psychological case updates `status` only, so the statistics panel's
  "completed this week" never counts psychological cases and the column stays
  NULL. Help requests set `completed_at`/`cancelled_at` on the same transition.
  Belongs with Phase 5 CS-1 (consultation records), which decides what a closed
  case records. Found during B-4.

## Registration

- **The registration page has no verification-code step.** The backend has been
  two-step since May 2026 (`POST /api/auth/register` sends a code,
  `POST /api/auth/register/verify` creates the account), but `register.html` stops
  after step 1 with the "code sent" message and there is no page to enter the code.
  A person can only complete registration through the API today; the gate smoke
  path does exactly that. Needs a code-entry step on `register.html` (mirroring
  `forgot-password.html`), then a browser check for register -> verify -> login.
  Not in the master plan; found during P-2.

## Provider approval (Phase 4, A-2 / UX-2)

- **Approval does not verify psychologists.** `approveUser` inserts the psychologist
  row with `is_on_duty = true` but leaves `is_verified` at its default `false`, and
  crisis routing selects only verified on-duty psychologists. On a fresh database no
  crisis case is routed until someone sets the flag by SQL. Decide whether admin
  approval implies professional verification (set it at approval) or whether the
  duty toggle UI (UX-2) should expose verification separately. Found at Gate 3.

## Operations

- **Shared rate-limit store** if the application is ever run on more than one node.
- **Flyway** for the migrations (Phase 4, DEP-1); until then
  `database/migrations/README.md` is the order of record.
