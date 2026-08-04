# Database Migrations

The application uses `spring.jpa.hibernate.ddl-auto=none`, so schema migrations
must be applied manually to PostgreSQL. The repository does not yet contain a
complete V1 bootstrap schema; load the existing base schema before these files.

Apply migrations once in filename order:

```bash
psql -U postgres -d Web_DB -f database/migrations/V2__matching_and_assignment_history.sql
psql -U postgres -d Web_DB -f database/migrations/V3__location_resources_and_message_moderation.sql
psql -U postgres -d Web_DB -f database/migrations/V4__message_types_and_community_channel.sql
```

## Versions

- `V2` adds assignment-history support and converts older psychological assignment
  records identified by the `PSYCHOLOGICAL_REQUEST` note.
- `V3` establishes canonical profile-location intent, provider resources, volunteer
  occupation, message soft deletion, and the `message_deletions` audit table.
- `V4` requires every message insert to choose `DIRECT` or `COMMUNITY`, makes
  `receiver_id` conditionally nullable, and enforces receiver/category rules with
  database checks.

`V4` has no `message_type` default and assumes the `messages` table is empty when it
is applied. Do not rerun V4 after it succeeds.
