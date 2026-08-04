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

1. `V2__matching_and_assignment_history.sql`
2. `V3__location_resources_and_message_moderation.sql`
3. `V4__message_types_and_community_channel.sql`
4. `V5__provider_availability_preference.sql`

The repository does not yet contain a complete V1 bootstrap migration. A new empty
database still needs the existing base schema before these migrations are applied.

V3 adds provider resources, volunteer occupation, message soft deletion, and the
message-deletion audit table. V4 separates direct messages from community messages
without overloading `receiver_id`. V5 adds organization availability and preserves
each provider's manual availability preference across assignment claims.

## Request And Matching Domains

Material help requests are represented by `HelpRequest`. Priority scoring combines
urgency, vulnerability flags, people count, and waiting time. Candidate matching
builds one pool of available volunteers and organizations, filters both roles by
provider resource type, then orders the combined pool by geographic distance.

`profiles.latitude` and `profiles.longitude` are the single source of truth for
provider location. Matching never reads the legacy volunteer coordinate columns.
A provider without both profile coordinates is omitted from geographic matching.
The nearest candidate is claimed atomically, the request moves from `PENDING` to
`ASSIGNED`, and an `AUTO_GEO` assignment stores exactly one of `volunteer_id` or
`organization_id`. A failed request update releases the winning claim.

Psychological requests use a separate lifecycle. Crisis detection can flag urgent
language and route eligible requests toward on-duty psychologists. This path is not
part of provider-resource matching.

Assignment history records material and psychological assignment events so admin
analytics can measure waiting time, utilization, regional fairness, and strategy
outcomes.

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

Numeric capacity is not decremented or reserved after assignment. Cancellation and
completion therefore do not restore inventory, because no inventory is consumed in
the current model.

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
qualitative-capacity unknown state.

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

- There is no complete V1 schema migration or automatic Flyway/Liquibase runner.
- Direct-message controllers/services are not operational yet, although the entity
  and repository path remain available and are isolated by `MessageType.DIRECT`.
- Community likes and comments are browser-local; only the message feed and
  moderation history are multi-user backend features.
- Community photo uploads are not modeled in the backend.
- Numeric provider capacity is compared with request `peopleCount` for ranked/admin
  visibility, but it is not decremented or reserved transactionally.
- Existing providers with only legacy volunteer coordinates must save matching
  coordinates in `profiles` before they can participate in geographic matching.
