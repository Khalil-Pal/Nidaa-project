# ADR 005 — Reserved capacity is consumed by completion and restored only by cancellation

**Status:** Accepted, 15 September 2026 (owner's decision after Gate 4).
**Supersedes:** the `unrestored_capacity_on_closed` invariant in the Master Plan appendix.

## Context

A provider (volunteer or organization) declares a resource with a numeric capacity,
for example `FOOD`, 50 people. When a help request is assigned to that provider,
`ProviderResourceService.reserveForAssignment()` locks the resource row and deducts
`min(capacityAmount, peopleCount)`; the assignment row records `resource_user_id`,
`resource_help_type` and the exact `reserved_capacity_amount`. Every assignment ends
in one of two terminal states, `COMPLETED` or `CANCELLED`.

The Master Plan's invariant set (Appendix, checked at every gate) contained:

```sql
SELECT 'unrestored_capacity_on_closed', count(*) FROM assignments
  WHERE status IN ('COMPLETED','CANCELLED')
    AND reserved_capacity_amount IS NOT NULL AND capacity_restored_at IS NULL
```

i.e. *every* closed assignment must have had its capacity restored. The code and
`docs/BACKEND_GUIDE.md` ("Remaining Capacity Boundary") say the opposite for
`COMPLETED`. At Gate 3 and again at Gate 4 the count was 2; both rows were
`COMPLETED` assignments and no `CANCELLED` row was unrestored. One of the two had to
change.

## Decision

Reserved capacity models *stock that will be handed over*. Completion means the
handover happened, so the stock is gone; cancellation means it did not, so the
stock comes back.

- `COMPLETED` keeps `reserved_capacity_amount` consumed and leaves
  `capacity_restored_at` NULL. Nothing is added back to `provider_resources`.
- `CANCELLED` restores exactly `reserved_capacity_amount` once, guarded by the
  conditional `UPDATE ... SET capacity_restored_at = :now WHERE capacity_restored_at
  IS NULL` in `AssignmentRepository.markCapacityRestored()` so that a race or a
  retry cannot restore twice (`HelpRequestService.restoreAssignmentCapacity()`).
- A provider who wants to serve more people after completing an assignment raises
  the resource's capacity themselves (`PUT /api/provider-resources`); the system does
  not guess that a completed contribution replenished itself.

The invariant is amended to state the design rather than contradict it:

```sql
UNION ALL SELECT 'unrestored_capacity_on_cancelled', count(*) FROM assignments
  WHERE status = 'CANCELLED'
    AND reserved_capacity_amount IS NOT NULL AND capacity_restored_at IS NULL
UNION ALL SELECT 'restored_capacity_on_completed', count(*) FROM assignments
  WHERE status = 'COMPLETED' AND capacity_restored_at IS NOT NULL
```

The second query is new: since a request cannot move from `CANCELLED` to
`COMPLETED`, a completed assignment with restored capacity can only be a bug.

## Alternatives considered

1. **Restore on completion too** (make the code match the plan's query). Rejected:
   it would report a provider's 50 meals as still available after they were eaten,
   so the matcher would keep sending people to a provider who has nothing left.
   Capacity would become a per-assignment concurrency limit, which the system already
   has separately in `is_available` and the active-assignment count
   (`releaseVolunteerIfIdle`, `releaseOrganizationIfIdle`).
2. **Drop the invariant.** Rejected: the cancellation path has a real double-restore
   race that the guarded update prevents, and the invariant is the only gate check
   that would notice a regression there.
3. **A CHECK constraint** `(status <> 'COMPLETED' OR capacity_restored_at IS NULL)`.
   Not done now: the four existing CHECK constraints on `assignments` are all about
   row shape (which references and which assignee a row may carry), not about the
   lifecycle; adding a lifecycle constraint is a schema decision for Phase 5's
   assignment work, recorded in `FUTURE_WORK.md`.

## Consequences

- `scripts/gate/invariants.sql` has 15 checks instead of 14; both new counts are 0
  on the development database and on the fresh gate database that carries the two
  `COMPLETED` reservations the old query flagged. The file is now tracked: until this
  change the repository's `*.sql` ignore rule had kept it (and would have kept any
  new migration) out of every commit.
- Gate 3's and Gate 4's one open FAIL is closed by this record; the gate records
  keep their original wording and point here.
- `docs/BACKEND_GUIDE.md` already describes this behaviour and is unchanged.
