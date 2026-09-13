# Database Migrations

The application uses `spring.jpa.hibernate.ddl-auto=none`, so schema migrations
must be applied manually to PostgreSQL.

Apply migrations once in filename order:

```bash
psql -U postgres -d Web_DB -f database/migrations/V1__base_schema.sql
psql -U postgres -d Web_DB -f database/migrations/V2__matching_and_assignment_history.sql
psql -U postgres -d Web_DB -f database/migrations/V3__location_resources_and_message_moderation.sql
psql -U postgres -d Web_DB -f database/migrations/V4__message_types_and_community_channel.sql
psql -U postgres -d Web_DB -f database/migrations/V5__provider_availability_preference.sql
psql -U postgres -d Web_DB -f database/migrations/V6__provider_capacity_reservations.sql
psql -U postgres -d Web_DB -f database/migrations/V7__assignment_assignee_constraints.sql
psql -U postgres -d Web_DB -f database/migrations/V8__drop_legacy_volunteer_coordinates.sql
psql -U postgres -d Web_DB -f database/migrations/V9__token_invalidation.sql
psql -U postgres -d Web_DB -f database/migrations/V10__reset_attempt_limit.sql
psql -U postgres -d Web_DB -f database/migrations/V11__soft_delete_users.sql
psql -U postgres -d Web_DB -f database/migrations/V13__filed_by.sql
```

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
