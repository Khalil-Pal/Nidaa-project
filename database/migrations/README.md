# Database Migrations

The application uses `spring.jpa.hibernate.ddl-auto=none`, so schema migrations
must be applied to PostgreSQL before starting the matching/history version.

Apply the files in version order. For this release:

```bash
psql -U postgres -d Web_DB -f database/migrations/V2__matching_and_assignment_history.sql
```

`V2` is idempotent and converts older psychological assignment records that were
identified by the `PSYCHOLOGICAL_REQUEST` note.
