# Nidaa Backend Guide

This guide describes the Spring Boot backend as it exists in the repository. It
focuses on runtime boundaries, authorization, persistence, matching, community
messages, and the extension pattern used by current features.

## Runtime Architecture

- Java 17 and Spring Boot 3.2.
- Spring MVC REST controllers under `/api`.
- Spring Security with stateless JWT authentication.
- Spring Data JPA and PostgreSQL persistence.
- Bean Validation for request DTOs.
- Static frontend files served from `src/main/resources/static`.
- Manual versioned SQL migrations under `database/migrations`.

The normal request path is:

```text
HTTP request -> controller -> DTO validation -> service authorization/business rules
             -> repository -> PostgreSQL -> response DTO -> JSON response
```

Controllers define the HTTP contract. Services own role checks, ownership checks,
normalization, lifecycle rules, and transactions. Repositories contain persistence
queries. Entities are not used as unrestricted public API contracts when doing so
would expose private fields or lazy relationships.

## Authentication And Authorization

`JwtAuthenticationFilter` reads the bearer token and establishes the authenticated
user. Method-level security is enabled through `@EnableMethodSecurity`.

Use both layers for protected behavior:

1. `@PreAuthorize` rejects disallowed roles at the controller boundary.
2. The service repeats important role and ownership checks so callers cannot bypass
   the business rule by invoking a service from another controller or test path.

`UserService.getCurrentUser()` resolves the authenticated email from the security
context and loads the corresponding `User`.

## Database And Migrations

Hibernate schema generation is disabled with `spring.jpa.hibernate.ddl-auto=none`.
Database changes must be applied manually in filename order:

1. `V1__base_schema.sql`
2. `V2__matching_and_assignment_history.sql`
3. `V3__location_resources_and_message_moderation.sql`
4. `V4__message_types_and_community_channel.sql`
5. `V5__provider_availability_preference.sql`
6. `V6__provider_capacity_reservations.sql`
7. `V7__assignment_assignee_constraints.sql`
8. `V8__drop_legacy_volunteer_coordinates.sql`

V1 contains the complete pre-V2 PostgreSQL schema, including enums, functions,
tables, sequences, constraints, indexes, triggers, and foreign keys. Applying V1
through V8 to an empty verification database produced a schema dump identical to
the migrated development database.

Before applying V4 to any existing environment, `SELECT COUNT(*) FROM messages`
must return zero. V4 has no message-type default or backfill. The already-applied
file remains unchanged to avoid checksum drift; stop and plan an explicit data
classification migration if rows already exist.

V3 adds provider resources, volunteer occupation, message soft deletion, and the
message-deletion audit table. V4 separates direct messages from community messages
without overloading `receiver_id`. V5 adds organization availability and preserves
each provider's manual availability preference across assignment claims. V6 records
numeric-capacity reservations. V7 makes automatic/human assignment authorship and
role-specific assignee columns explicit database invariants. V8 removes the old
volunteer coordinate columns after checking that they contain no non-null data.
An upgrading environment with values in those columns must migrate them to the
corresponding user profiles before V8 can succeed.

## Request And Matching Domains

Material help requests are represented by `HelpRequest`. Priority scoring combines
urgency, vulnerability flags, people count, and waiting time. Candidate matching
builds one pool of available volunteers and organizations, filters both roles by
provider resource type, then orders the combined pool by geographic distance.

`profiles.latitude` and `profiles.longitude` are the single source of truth for
provider location. V8 removed the duplicate volunteer coordinate columns, so the
schema and matching service now enforce one location source. A provider without
both profile coordinates is omitted from geographic matching.
The nearest candidate is claimed atomically, the request moves from `PENDING` to
`ASSIGNED`, and an `AUTO_GEO` assignment stores exactly one of `volunteer_id` or
`organization_id`. A failed request update releases the winning claim.

Psychological requests use a separate lifecycle. Crisis detection can flag urgent
language and route eligible requests toward on-duty psychologists. This path is not
part of provider-resource matching.

Assignment history records material and psychological assignment events so admin
analytics can measure waiting time, utilization, regional fairness, and strategy
outcomes.

V7 enforces the assignment shape directly in PostgreSQL:

- `HELP_REQUEST` has exactly one of `volunteer_id` or `organization_id`, and no
  psychologist.
- `PSYCHOLOGICAL_REQUEST` has exactly one psychologist, and no volunteer or
  organization placeholder.
- `AUTO_GEO` and `AUTO_CRISIS` have `assigned_by = NULL`.
- `MANUAL` and `ADMIN` assignments require the responsible user's ID.

Real PostgreSQL integration tests flush and reload automatic material and
psychological assignment rows. This protects the nullable-column contract without
mocking `AssignmentRepository`.

## Provider Resources

`provider_resources` stores one normalized help-type capacity per volunteer or
organization. The service accepts these capacity modes:

- `NUMERIC`: a positive `capacityAmount` is required.
- `QUALITATIVE`: a nonblank `capacityLabel` is required.

The unique `(user_id, help_type)` constraint makes writes an upsert rather than a
duplicate insert. Role enforcement is performed in `ProviderResourceService`.

Request-aware capacity assessment is exposed to ranked material-request responses
through `capacityMode`, `capacityAmount`, and tri-state `capacitySufficient`:

| `capacitySufficient` | Meaning |
|---|---|
| `true` | Numeric capacity is greater than or equal to `HelpRequest.peopleCount` |
| `false` | Numeric capacity is positive but below `HelpRequest.peopleCount` |
| `null` | Capacity is qualitative, the request count is unavailable, or no provider is suggested |

An insufficient numeric provider remains eligible. Automatic assignment still
selects the nearest eligible provider, and manual acceptance still requires only a
usable matching resource. The comparison is an admin-visible planning signal; it
does not change provider ordering or reject partial contributions.

### Remaining Capacity Boundary

Numeric capacity is transactionally reserved during both automatic and manual
assignment. The provider-resource row is pessimistically locked, and the service
deducts `min(capacityAmount, peopleCount)` so a confirmed partial provider remains
usable without producing negative inventory.

The assignment stores `resource_user_id`, `resource_help_type`, and the exact
`reserved_capacity_amount`. `COMPLETED` means the contribution was fulfilled and
keeps that amount consumed. `CANCELLED` is the non-fulfilled terminal outcome: it
atomically marks `capacity_restored_at` and adds the exact amount back once. Active
reservations prevent the matching provider-resource row from being edited or
deleted until the assignment reaches a terminal state.

## Provider Availability And Location

Volunteers and organizations manage their own availability through:

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `GET` | `/api/provider-availability/me` | Volunteer, organization | Read effective and preferred availability plus active assignment count |
| `PUT` | `/api/provider-availability/me` | Volunteer, organization | Set the provider's own availability preference |
| `GET` | `/api/users/me/profile` | Authenticated | Load the server-backed profile and matching coordinates |
| `PUT` | `/api/users/me/profile` | Authenticated | Save profile fields; latitude and longitude must be supplied together |

`is_available` is the effective claim flag. `availability_preference` is the
provider's explicit choice. Claiming sets only the effective flag to false;
releasing the final active assignment restores it from the preference. A provider
may explicitly set availability to true while an assignment remains active, which
does not alter or close that assignment.

## Message And Community Model

The `messages` table supports two explicitly different message types. Every insert
must set `message_type`; there is no database or Java default.

| Type | Purpose | `receiver_id` | `community_category` |
|---|---|---|---|
| `DIRECT` | Private user-to-user conversation | Required | Must be `NULL` |
| `COMMUNITY` | Shared responder/admin community feed | Must be `NULL` | Optional normalized feed category |

PostgreSQL enforces the addressing invariant:

```text
(DIRECT and receiver_id is not null)
or
(COMMUNITY and receiver_id is null)
```

A second check prevents `community_category` from being stored on a direct message.
The existing private-conversation repository methods explicitly filter
`MessageType.DIRECT`. Community repository methods explicitly filter
`MessageType.COMMUNITY` and `is_deleted = false`, so direct or moderated content
cannot leak into the feed.

### Community Categories

`CommunityCategoryNormalizer` preserves the categories already present in
`community.html` and stores uppercase underscore values:

- `UPDATE`
- `SUCCESS_STORIES`
- `QUESTION`
- `TIPS_ADVICE`
- `EVENTS`
- `RESOURCES`
- `GRATITUDE`
- `INFO`

Missing categories use the existing `UPDATE` category. Unknown categories are
rejected with `400 Bad Request`.

### Community Authorization

`VOLUNTEER`, `PSYCHOLOGIST`, `ORGANIZATION`, and `ADMIN` users may list and create
community messages. Administrators are intentionally allowed to post status and
policy updates. `BENEFICIARY` users are rejected with `403` at both controller and
service boundaries.

Only an administrator may delete a community message. A nonblank justification is
mandatory. Deletion runs in one transaction:

1. Load a visible `COMMUNITY` message.
2. Set `messages.is_deleted = true`.
3. Insert `message_deletions` with message ID, admin ID, original author ID,
   normalized reason, original-content snapshot, and deletion timestamp.

The original-content snapshot makes the audit independent of later message-content
changes. The current foreign key prevents physical message purging; supporting a
future purge while retaining audits would also require an explicit FK-policy
migration.

### Community Endpoints

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `GET` | `/api/community/messages?page=0&size=20` | Responder roles, admin | Paginated visible community messages only |
| `POST` | `/api/community/messages` | Responder roles, admin | Create an explicit `COMMUNITY` message |
| `DELETE` | `/api/community/messages/{id}?reason=...` | Admin | Soft-delete and create an audit snapshot |
| `GET` | `/api/admin/community/deletions?page=0&size=20` | Admin | Paginated moderation audit history |

Create request example:

```json
{
  "content": "Medical supplies are available this afternoon.",
  "communityCategory": "UPDATE"
}
```

Message responses expose the message ID, author ID/name/role, content, normalized
category, and timestamp. They never expose `receiver_id`, direct-message content,
or internal deletion flags.

## Contact Reveal

Contact services reveal phone/email only to the request owner and assigned provider.
Assigned volunteers and organizations can access material-request contacts.
Psychological contacts are available to the requester and assigned psychologist,
but anonymous psychological requests never reveal beneficiary identity or contact
details to the psychologist.

## Error Contract

`GlobalExceptionHandler` maps validation and business failures to structured JSON.
Important mappings include:

- Validation or business-rule failure: `400`.
- Missing/invalid authentication: `401` at the security layer.
- Disallowed role or ownership: `403`.
- Missing domain record: `404`.
- Unexpected persistence/runtime failure: `500`.

## Testing

Run all tests with:

```powershell
.\mvnw.cmd test
```

Community coverage includes role enforcement, mandatory moderation reasons, audit
snapshot creation, soft-deleted-message exclusion, direct-message exclusion, and
admin audit retrieval. Matching coverage includes combined provider ranking,
resource and availability filters, atomic claim cleanup, availability authorization,
profile-location save/load behavior, request-aware numeric capacity flags, and the
qualitative-capacity unknown state. Reservation coverage includes partial and full
deductions, assignment-race restoration, cancellation restoration, completion
retention, and active-reservation edit protection.

## Extension Pattern

For a backend feature:

1. Add a versioned migration when persistence changes.
2. Map the schema with an entity and repository query.
3. Define validated request and safe response DTOs.
4. Put normalization, authorization, and transactions in a service.
5. Expose a focused controller endpoint.
6. Test role failures, validation failures, successful persistence, and visibility.
7. Update this guide, the frontend guide, and README endpoint/migration notes.

## Known Boundaries

- Migrations are complete but still applied manually; there is no automatic
  Flyway/Liquibase runner.
- Direct-message controllers/services are not operational yet, although the entity
  and repository path remain available and are isolated by `MessageType.DIRECT`.
- Community likes and comments are browser-local; only the message feed and
  moderation history are multi-user backend features.
- Community photo uploads are not modeled in the backend.
