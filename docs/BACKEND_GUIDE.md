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

From `ASSIGNED` the assigned provider (or an admin) may mark the request
`IN_PROGRESS` — "on my way", one button on the card (W-1) — before `COMPLETED`;
`ASSIGNED → COMPLETED` still works directly for short deliveries. The transition
is a guarded UPDATE like the others, the other parties are notified on entry,
and the assignment row stays `ASSIGNED` (capacity and availability follow the
assignment, not the request). The shared map is `RequestTransitions`;
psychological cases do not use `IN_PROGRESS`: material aid has a delivery
journey worth reporting, psychological support does not — the span between
assignment and completion is the support itself.

Psychological requests use a separate lifecycle. Crisis detection can flag urgent
language and route eligible requests toward psychologists who are both on duty and
professionally verified. Those are two separate decisions by two people: the
psychologist toggles duty themselves (`PUT /api/psychologists/me/duty`), and an
administrator records the credential check (`PUT
/api/admin/psychologists/{userId}/verification`, body `{"verified": true|false}`,
sets `is_verified`, `verified_at`, `verified_by` and writes an `activity_logs` row).
Approval alone creates the profile off duty and unverified, so a newly approved
psychologist receives no crisis case until both have happened. This path is not
part of provider-resource matching.

Assignment history records material and psychological assignment events so admin
analytics can measure waiting time, utilization, regional fairness, and strategy
outcomes.

The four matching strategies (`service.matching`: FIFO, weighted scoring,
geo-nearest, multi-objective) are compared two ways. `MatchingEvaluationService`
ranks the current pending queue and reports what each strategy would do with it
now (`GET /api/v1/admin/evaluation`). The study in
[`docs/evaluation/EV-1-matching-study.md`](evaluation/EV-1-matching-study.md)
(EV-1) runs the same classes in a discrete-event simulation over 72 hours of
arrivals and deliveries, across load and provider density, and reports means and
standard deviations over seeded repetitions. The simulation code is
`com.humanitarian.platform.evaluation`; it needs no database, and
`SyntheticDataGenerator` is the same generator that produces the dev profile's
sample requests (`DataSeeder`). `PriorityScoreService` takes a `Clock` so the
priority model ages requests in simulated time; the application uses the system
clock.

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
| `POST` | `/api/community/messages/{id}/like` | Responder roles, admin | Like the post; idempotent, answers `{likedByMe, likeCount}` (CM-1) |
| `DELETE` | `/api/community/messages/{id}/like` | Responder roles, admin | Remove the caller's like; idempotent (CM-1) |
| `GET` | `/api/community/messages/{id}/comments?page=0&size=50` | Responder roles, admin | Visible comments on the post, oldest first (CM-1) |
| `POST` | `/api/community/messages/{id}/comments` | Responder roles, admin | `{"content"}`, the same 1000-character cap as a post; 201 (CM-1) |
| `DELETE` | `/api/community/messages/{id}/comments/{commentId}?reason=...` | Admin | Soft-delete the comment, audit it in `message_deletions` with `comment_id`, notify its author (CM-1) |
| `GET` | `/api/admin/community/deletions?page=0&size=20` | Admin | Paginated moderation audit history; a row with `commentId` is a removed comment |

Create request example:

```json
{
  "content": "Medical supplies are available this afternoon.",
  "communityCategory": "UPDATE"
}
```

Message responses expose the message ID, author ID/name/role, content, normalized
category, timestamp and, since CM-1, `likeCount`, `commentCount` and `likedByMe`
(three grouped queries per page in `CommunityEngagementService.summarize()`).
They never expose `receiver_id`, direct-message content, or internal deletion
flags. `message_reactions` has one row per (post, user) — the pair is its primary
key, so a second like is refused by the database as well as by the service, and
like/unlike are idempotent. The role gate, the 1000-character cap and the
moderator's reason live in `CommunityRules`, shared by posts and comments.

## Completion Reports

`AssignmentReportService` (R-1) records what a volunteer delivered and how the
beneficiary rated it, on the `reports` row of the assignment. The assigned
volunteer may record once the assignment is `COMPLETED`; exactly one report per
assignment (`UNIQUE (assignment_id)`, V19: a second submission is 409 whether the
service or the database catches it). The beneficiary rates once (1–5, optional
text) after the report exists; beneficiary, assigned provider and administrators
may read it, and anyone else gets 404 for any assignment id, the same rule as the
request itself. Recording notifies the beneficiary (and the filer) to rate;
rating notifies the volunteer.

`reports.volunteer_id` is `NOT NULL`: the table records volunteer deliveries, so
an organization's completed assignment has no report row to write (400 with a
plain message; `FUTURE_WORK.md`). `photos` stays unused: file upload is its own
security surface and no endpoint accepts one.

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `POST` | `/api/assignments/{id}/report` | Assigned volunteer | `{"description"}`; assignment must be COMPLETED; one per assignment |
| `POST` | `/api/assignments/{id}/feedback` | Beneficiary of the request | `{"rating": 1..5, "feedback"?}`; once; needs the report |
| `GET` | `/api/assignments/{id}/report` | Beneficiary, assigned provider, admin | The report with the rating once given; 404 if none yet |

The assignment id comes from the request's history
(`GET /api/v1/assignments/help-requests/{requestId}`), which the pages already use.

## Consultation Records

`ConsultationService` (CS-1) records the sessions of a psychological case, one
`consultations` row per session: format (the `consultation_format` enum), when
it took place and for how long, the topics discussed (`text[]`, mapped as a
list), the recommendations, and the psychologist's private note. A case is a
course of support: it stays `ASSIGNED` until the psychologist completes it, and
the assigned psychologist records a session while it is open or after it is
completed (never on a pending or cancelled case). V20 adds `assignment_id`, so a
session says which assignment it belongs to; V22 drops V20's one-per-case
`UNIQUE` (owner decision after Gate 5: the row's own `started_at`, `ended_at`
and `duration_minutes` are per-session fields, and one record per case would
have forced a second session through a new request that re-queues and may route
elsewhere). The beneficiary rates each session once (1–5, optional text);
beneficiary, assigned psychologist and administrators list the sessions oldest
first, and anyone else gets 404 for any request id, the same rule as the case
itself. Recording notifies the beneficiary to rate; rating notifies the
psychologist. `psychologists.consultation_count` counts sessions (AGG-1).
Completing a case stamps `psychological_requests.completed_at`, which the
statistics read.

Two rules matter more than the rest. **`notes_for_psychologist` is private to the
psychologist**: the response is built with it only when the caller is the assigned
psychologist; for the beneficiary and for administrators the key is omitted from
the JSON, not sent as null. **The response and the notifications carry no
beneficiary identity** (no id, no name), so an anonymous case stays anonymous
through feedback as well: the psychologist learns the rating, never who gave it.

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `POST` | `/api/psychological-requests/{id}/consultations` | Assigned psychologist | `{"format", "startedAt"?, "durationMinutes"?, "topicsDiscussed"?, "recommendations"?, "notesForPsychologist"?}`; case ASSIGNED or COMPLETED (400 otherwise); **201** with the session; the case's status does not change |
| `POST` | `/api/psychological-requests/{id}/consultations/{consultationId}/feedback` | Beneficiary of the case | `{"rating": 1..5, "feedback"?}`; once per session (409 again); 404 when the session is not this case's; anonymous cases may rate |
| `GET` | `/api/psychological-requests/{id}/consultations` | Beneficiary, assigned psychologist, admin | The sessions oldest first (`startedAt`, then id), each with its rating once given; the private note only for the psychologist; an empty list before any |

`chat_session_id` stays unused: there is no chat system to reference.

## Provider Counters

`ProviderStatsService` (AGG-1) keeps the four denormalised columns real:
`volunteers.total_completed_requests` and `volunteers.rating` from the volunteer's
completion reports, `psychologists.consultation_count` and `psychologists.rating`
from the psychologist's consultation records. `AssignmentReportService` calls
`refreshVolunteer()` after a report is recorded and after it is rated;
`ConsultationService` calls `refreshPsychologist()` at the same two points, inside
the same transaction as the source row.

Every refresh recomputes both values in full (`COUNT`, `AVG` over the rated rows)
rather than incrementing or averaging a new rating into the old one, so a corrected
rating produces the new true mean. The mean is rounded to hundredths, the precision
of the `numeric(3,2)` columns, so the persisted value equals the computed one; no
ratings is `NULL` (D-3, V18), never 0. The count is the number of reports or
consultation records, which is what the plan defines it as: a completed assignment
without a report is not counted. Nothing in matching reads these columns.

## Notifications

`NotificationService` (N-1) writes a row to `notifications` inside the transaction
of the event it announces, so the notification exists exactly when the event does.
Emitted for: a help request accepted manually or matched automatically (to the
beneficiary and, when someone filed it for them, the filer; the matched provider
too), every status change (to each party other than the actor), a psychological
case accepted, a crisis case routed (to the psychologist and the person), an
account approved, a provider's numeric capacity reaching zero, and a community
post removed by a moderator (with the reason, not the moderator's name). A
rejected application cannot be notified in-app because rejection deletes the
account row; the e-mail the controller sends is the only channel for it.

Only `IN_APP` is delivered: the row is stored as `SENT` and the browser polls for
it. The `notification_type` enum also lists `EMAIL`, `SMS` and `PUSH`; the model
anticipates those channels and nothing sends them.

| Method | Endpoint | Behavior |
|---|---|---|
| `GET` | `/api/notifications?page=&size=` | The caller's notifications, newest first, paged (max 50) |
| `GET` | `/api/notifications/unread-count` | `{"unread": n}` for the caller |
| `PUT` | `/api/notifications/{id}/read` | Marks one of the caller's notifications read; someone else's id is **404** |
| `PUT` | `/api/notifications/read-all` | Marks all of the caller's unread notifications read; returns the count |

Every call is scoped to the authenticated user inside the service; there is no
admin view of other people's notifications.

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

- Migrations are applied by Flyway at start-up from `src/main/resources/db/migration`
  (DEP-1); databases built by hand before that are baselined at V18.
- Direct-message controllers/services are not operational yet, although the entity
  and repository path remain available and are isolated by `MessageType.DIRECT`.
- Community photo uploads are not modeled in the backend.
