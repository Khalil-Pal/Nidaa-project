# Architecture decision records

One file per decision, numbered in the order they were taken. A record is never
edited after acceptance except to add a *Superseded by* line.

| # | Decision | Status |
|---|---|---|
| 001 | Monolith over microservices | planned (Phase 7, DOC-*) |
| 002 | No role claim in the JWT; the role is read from the database on every request | planned (Phase 7, DOC-*) |
| 003 | Pessimistic locking for capacity, optimistic (guarded UPDATE) for claiming and status transitions | planned (Phase 7, DOC-*) |
| 004 | Hybrid entity / foreign-key relationship style | planned (Phase 7, DOC-*) |
| [005](005-capacity-consumed-on-completion.md) | Reserved capacity is consumed by completion and restored only by cancellation | accepted 2026-09-15 |

001–004 are listed in the Master Plan's documentation set and are written in
Phase 7; 005 was taken earlier because it closed the one open FAIL from Gates 3
and 4.
