# Nidaa — Database

This document is built up over the project. The schema inventory below is
maintained at every gate (Master Plan, G3); the data dictionary and the entity
relationship diagram are added in Phase 7.

Schema management: `spring.jpa.hibernate.ddl-auto=none`; the schema comes only from
`src/main/resources/db/migration/V*.sql`, applied in version order by Flyway at
start-up (DEP-1; see `database/migrations/README.md` for the rules and the
version notes). `scripts/gate/fresh-db.sh` builds a database from those files
alone with `./mvnw flyway:migrate` and prints the inventory below.

The versions run V1–V11 and V13–V18: **V12 was never issued**. The gap is
deliberate history, not a missing file; Flyway does not require consecutive
numbers and `flyway_schema_history` records exactly the files that exist.

## Trigger, function and view inventory

Recorded after Phase 3 (migrations V1–V15). Every entry must be expected and
explained; anything not listed here is a defect until proven otherwise.

| Kind | Name | Purpose | Interaction with the application |
|---|---|---|---|
| function | `update_updated_at_column()` | Sets `NEW.updated_at = CURRENT_TIMESTAMP` | Used only by the six triggers below |
| trigger | `users.update_users_updated_at` BEFORE UPDATE | Refresh `updated_at` | The `User` entity also sets `updated_at` through `@UpdateTimestamp`; both write "now", the trigger's value wins on UPDATE. Redundant, not conflicting. |
| trigger | `help_requests.update_help_requests_updated_at` BEFORE UPDATE | same | same, `HelpRequest` |
| trigger | `psychological_requests.update_psychological_requests_updated_at` BEFORE UPDATE | same | same, `PsychologicalRequest` |
| trigger | `profiles.update_profiles_updated_at` BEFORE UPDATE | same | same, `Profile` |
| trigger | `psychologists.update_psychologists_updated_at` BEFORE UPDATE | same | same, `Psychologist` |
| trigger | `self_help_materials.update_self_help_materials_updated_at` BEFORE UPDATE | same | Table is unwired (future scope) |
| view | — | none | — |

**Removed in V15:** `calculate_priority_score()` and the triggers
`calculate_priority_before_insert` / `calculate_priority_before_update` on
`help_requests`. They recomputed `priority_score` with weights that contradicted the
documented model in `PriorityScoreService`, on every INSERT and on every Hibernate
UPDATE. `PriorityScoreService` is now the only writer of `priority_score`, and the
persistence tests assert persisted == computed.

**Not part of the schema:** the `pgcrypto` extension. `database/migrations/README.md`
offers it as one optional way to hash the first administrator's password by hand; the
application never calls it. A database that has it installed shows its functions in
the inventory; they can be removed with `DROP EXTENSION pgcrypto`.

## Columns written by more than one party

The gate rule is that a trigger writing a column the application also writes is a
defect until proven otherwise. After V15 the only such columns are the six
`updated_at` columns above. Proof of harmlessness: both writers set the current
timestamp; the trigger runs BEFORE UPDATE only, so inserts carry Hibernate's value
and updates carry the database's; no code path reads `updated_at` for a decision.

## Computed-and-stored values

| Column | Computed by | Persisted == computed asserted by |
|---|---|---|
| `help_requests.priority_score` | `PriorityScoreService.calculate(HelpRequest)` at creation; `PriorityScoreScheduler` every 30 minutes | `HelpRequestPersistenceTest.priorityScoreIsStoredAsTheApplicationComputedIt` |
| `psychological_requests.is_crisis`, `needs_review` | `CrisisDetectorService.assess()` at creation | `CrisisDetectorServiceTest` (scoring) and `PsychologicalRequestSecurityTest` (wiring) |

## Invariants

`scripts/gate/invariants.sql` holds the invariant query set from the Master Plan
appendix. Every count must be zero on every database at every gate.
