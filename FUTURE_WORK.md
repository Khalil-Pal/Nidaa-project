# Future Work

Ideas and follow-ups that surfaced during the audit remediation but were not part of
the agreed scope. Each entry says what it would do and why it was deferred, so that
"not done" reads as "planned". The two unwired pipelines named in the roadmap
(group sessions, self-help materials and request media) are added here after
Phase 5.

## Security

- **Strict `script-src`.** Move each page's inline script into `js/<page>.js` and
  replace the ~200 inline `onclick` attributes with event listeners, then drop
  `'unsafe-inline'` from the Content-Security-Policy. Deferred because it is the same
  handler rewrite the accessibility pass (F-5) performs; doing it twice would be
  wasted work.
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

- **Volunteer and psychologist rating default.** The entities default `rating` to
  `0.0` while the database requires 1..5, so a profile persisted through JPA with
  the default fails. Scheduled as Phase 4 task D-3 (default `null`, CHECK allowing
  NULL).
- **Stories on the server.** Success stories are stored in the browser's
  `localStorage`, so they are per browser and moderation is local too. The
  community feed already has the server-side pattern (`MessageService`) to copy.

## Operations

- **Shared rate-limit store** if the application is ever run on more than one node.
- **Flyway** for the migrations (Phase 4, DEP-1); until then
  `database/migrations/README.md` is the order of record.
