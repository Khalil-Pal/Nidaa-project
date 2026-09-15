# Database Migrations

The schema is owned by the versioned SQL files in
`src/main/resources/db/migration/` and applied by **Flyway** when the application
starts (Phase 4, DEP-1). `spring.jpa.hibernate.ddl-auto=none`: Hibernate never
changes the schema.

- An **empty database** receives every migration from `V1` in version order.
- A database that was **built by hand before Flyway** (any schema, no
  `flyway_schema_history` table) is *baselined* at version 18 on first start:
  Flyway records that V1–V18 are already present and runs only later versions.
- `flyway_schema_history` is the record of what ran; `SELECT version,
  description, success FROM flyway_schema_history ORDER BY installed_rank`.

Without starting the application:

```bash
./mvnw flyway:info    -Dflyway.url=jdbc:postgresql://localhost:5432/Web_DB -Dflyway.user=postgres -Dflyway.password=...
./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/Web_DB -Dflyway.user=postgres -Dflyway.password=...
```

`scripts/gate/fresh-db.sh` builds a database from the migrations alone this way
and checks the history against the files (gate check G2).

Rules:

- **Never edit a migration that has shipped.** Flyway stores a checksum of each
  file and refuses to start if one changes. Add a new `V<n>__<name>.sql` instead.
  (The files were adjusted once, when Flyway was introduced: pg_dump's psql-only
  `\restrict` lines and `search_path` reset were removed from V1, and the
  explicit `BEGIN;`/`COMMIT;` wrappers from V2–V18, because Flyway runs each
  migration in its own transaction. No database had a Flyway history yet.)
- Version numbers are integers; `V12` was never used and that is fine.
- One concern per migration, a comment at the top saying why.

The first administrator is still inserted by hand (see below).

## Versions

- `V1` creates the complete base schema: enums, functions, tables, sequences,
  constraints, indexes, triggers, and foreign keys required before V2.
- `V2` adds assignment-history support and converts older psychological assignment
  records identified by the `PSYCHOLOGICAL_REQUEST` note.
- `V3` establishes canonical profile-location intent, provider resources, volunteer
  occupation, message soft deletion, and the `message_deletions` audit table.
- `V4` requires every message insert to choose `DIRECT` or `COMMUNITY`, makes
  `receiver_id` conditionally nullable, and enforces receiver/category rules with
  database checks.
- `V5` adds effective availability to organizations and a durable manual
  availability preference to both provider roles. Claim release restores this
  preference instead of always forcing the provider available.
- `V6` records exact numeric-capacity reservations on material assignments so the
  assignment transaction can decrement inventory and cancellation can restore it
  exactly once.
- `V7` makes `volunteer_id` and `assigned_by` conditionally nullable and enforces
  exactly one role-appropriate assignee plus source-appropriate human authorship.
- `V8` removes the obsolete volunteer coordinate columns after all provider
  matching moved to `profiles`. It aborts instead of discarding any unexpected
  non-null legacy coordinates.
- `V9` adds `users.tokens_valid_from`. Access tokens issued before it are
  rejected, so changing or resetting a password ends every existing session.
  Idempotent; safe to rerun.
- `V10` adds `password_reset_tokens.attempts`; a reset token is deleted after
  five wrong codes. Idempotent; safe to rerun.
- `V11` makes `users.phone` nullable and drops its UNIQUE constraint so an
  account can be anonymised in place (soft delete) and households can share a
  number. Idempotent; safe to rerun.
- `V13` adds `help_requests.filed_by_user_id` for requests a volunteer or
  organization files on a beneficiary's behalf, with an index and a check that
  the filer is never the beneficiary. Existing rows stay NULL (self-filed).
  Idempotent; safe to rerun.
- `V14` adds `psychological_requests.needs_review` for descriptions that score
  in the review band of the weighted crisis detector (flagged for a human look,
  not auto-routed). Idempotent; safe to rerun.
- `V15` drops the V1 priority triggers, whose weights contradicted the
  documented model in `PriorityScoreService` and silently overrode every score
  the application computed. Scores already in the table are refreshed by the
  scheduler within 30 minutes. Idempotent; safe to rerun.
- `V16` makes `psychologists.specialization` and `organizations.registration_number`
  nullable. Admin approval inserts the profile row with only what registration
  collects, and both columns were NOT NULL without defaults, so approving a
  psychologist or an organization always failed on a fresh schema. Idempotent;
  safe to rerun.
- `V17` converts `users.role` from `character varying` to the `user_role` enum it
  was always meant to be; the application's native-query workarounds for the
  mismatch were deleted with it. Idempotent; safe to rerun.
- `V18` lets `volunteers.rating` and `psychologists.rating` be NULL ("not rated
  yet"), drops the misleading default of 5.0, and resets the default-only values
  already stored. Real ratings arrive with Phase 5 (AGG-1). Idempotent; safe to
  rerun.

V1 through V8 were applied in order to a genuinely empty verification database.
Its normalized schema dump matched the migrated development database with zero
differences.

## Creating the First Administrator

`POST /api/auth/register` refuses the `ADMIN` role (it would otherwise hand out
an admin token to anyone who can receive a verification email). Administrators
are therefore created directly in the database. Do this once, after the
migrations have been applied:

1. Generate a BCrypt hash of the chosen password. Any BCrypt tool works; the hash
   must start with `$2a$`, `$2b$` or `$2y$`. Two options:

   ```bash
   # Apache htpasswd (part of apache2-utils / httpd-tools)
   htpasswd -nbBC 10 "" 'ChangeMe-Now!' | tr -d ':\n'

   # Python (pip install bcrypt)
   python -c "import bcrypt; print(bcrypt.hashpw(b'ChangeMe-Now!', bcrypt.gensalt(10)).decode())"
   ```

2. Insert the user with that hash. `phone` is optional once V11 has been
   applied; on an older schema supply a placeholder.

   ```sql
   INSERT INTO users (email, password_hash, phone, full_name, role,
                      is_verified, is_active, is_locked)
   VALUES ('admin@example.org',
           '$2a$10$REPLACE_WITH_THE_HASH_FROM_STEP_1',
           '+000000000',
           'Platform Administrator',
           'ADMIN',
           true, true, false);
   ```

3. Log in through the normal `POST /api/auth/login` endpoint and change the
   password immediately from the Settings page.

Alternatively, if the `pgcrypto` extension is available, PostgreSQL can hash the
password itself:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO users (email, password_hash, phone, full_name, role,
                   is_verified, is_active, is_locked)
VALUES ('admin@example.org',
        crypt('ChangeMe-Now!', gen_salt('bf', 10)),
        '+000000000',
        'Platform Administrator',
        'ADMIN',
        true, true, false);
```

Never keep the plaintext password in a script or shell history.

## V4 Mandatory Preflight

V4 is a shipped migration and intentionally remains byte-for-byte unchanged. Before
its first application to any database that was not created immediately from V1,
run:

```sql
SELECT COUNT(*) AS message_rows_before_v4 FROM messages;
```

The result must be `0`. V4 adds required `message_type` without a default or
backfill; if the result is greater than zero, stop and design an environment-specific
classification/backfill migration before applying V4. Do not rerun V4 after it has
succeeded. A new empty database following V1 through V8 satisfies this precondition
automatically.

The warning is documented here instead of editing the already-applied V4 file and
creating migration-checksum drift. V5 uses `IF NOT EXISTS`, so rerunning it safely
reports notices for columns and indexes already present.
