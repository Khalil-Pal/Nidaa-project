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

V1 through V8 were applied in order to a genuinely empty verification database.
Its normalized schema dump matched the migrated development database with zero
differences.

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
