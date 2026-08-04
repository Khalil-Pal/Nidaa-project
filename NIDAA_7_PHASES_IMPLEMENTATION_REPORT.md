# Nidaa Seven-Phase Implementation Report

- **Project:** Nidaa Humanitarian Assistance Platform
- **Backend:** Java 17, Spring Boot 3.2, Spring Security, Spring Data JPA
- **Database:** PostgreSQL 17
- **Frontend:** Static HTML, CSS, and JavaScript served by Spring Boot
- **Report date:** 2026-08-05

## 1. Purpose Of This Work

The seven phases extended Nidaa from volunteer-only, partially browser-local
workflows into a server-backed provider platform with:

- structured resources for volunteers and organizations;
- resource-aware material-request matching;
- protected contact exchange after assignment;
- a real multi-user community feed with moderation history;
- canonical server-backed matching locations;
- manual provider availability controls; and
- combined distance-based automatic assignment across volunteers and
  organizations.

The work followed the existing Spring Boot layering:

```text
HTTP request
  -> controller and DTO validation
  -> service authorization and business rules
  -> repository
  -> PostgreSQL
  -> response DTO
  -> JSON/frontend rendering
```

## 2. Overall Result

| Phase | Main result | Status |
|---|---|---|
| 1 | Added the V3 location, provider-resource, occupation, and moderation schema | Complete |
| 2 | Added provider-resource and volunteer-occupation backend APIs | Complete |
| 3 | Added server-backed My Services, occupation, profile display, and setup prompt UI | Complete |
| 4 | Added resource eligibility to automatic, ranked, and manual material matching | Complete |
| 5 | Added protected material and psychological contact reveal, including anonymity | Complete |
| 6 | Added the real community backend, V4 message isolation, and admin moderation | Complete |
| 7 | Added V5 availability, matching-location UI, and combined provider geo-matching | Complete |
| Post-7 A | Added request-aware tri-state provider-capacity reporting without changing nearest-provider selection | Complete |

Current verification result:

```text
Maven test suites: 19
Tests:             81
Failures:          0
Errors:            0
Skipped:           0
```

The Spring application context also started successfully against the migrated
PostgreSQL database, and the updated `settings.html` script passed JavaScript
syntax validation.

---

## 3. Phase 1 - Database Foundation

### Goal

Create the persistence foundation without changing matching behavior yet. Keep
legacy volunteer coordinates temporarily while marking `profiles` as the future
location source for every role.

### Migration Added

File:

```text
database/migrations/V3__location_resources_and_message_moderation.sql
```

### Changes

#### Canonical profile location

- Confirmed nullable `profiles.latitude` and `profiles.longitude` storage.
- Added `idx_profiles_coordinates`.
- Added database comments documenting `profiles` as the canonical location for:
  - `BENEFICIARY`
  - `VOLUNTEER`
  - `ORGANIZATION`
  - `PSYCHOLOGIST`
- Kept `volunteers.latitude` and `volunteers.longitude` temporarily to avoid an
  unsafe behavior change in this phase.
- Marked the volunteer columns as legacy through database comments.

#### Provider resources

Added `provider_resources` with:

| Column | Purpose |
|---|---|
| `id` | Primary key |
| `user_id` | Volunteer or organization user |
| `help_type` | Normalized help type using the existing PostgreSQL `help_type` enum |
| `capacity_mode` | `NUMERIC` or `QUALITATIVE` |
| `capacity_amount` | Positive numeric capacity |
| `capacity_label` | Human-readable qualitative capacity |
| `created_at`, `updated_at` | Audit timestamps |

The migration also added:

- a unique constraint on `(user_id, help_type)`;
- a capacity-mode check constraint;
- a capacity-value check constraint;
- an index on `help_type`; and
- a table comment documenting application-layer role enforcement.

#### Volunteer occupation

Added:

```sql
volunteers.occupation VARCHAR(150) NULL
```

#### Community moderation storage

- Added `messages.is_deleted BOOLEAN NOT NULL DEFAULT false`.
- Added `message_deletions` with:
  - the moderated message ID;
  - deleting administrator ID;
  - original author ID;
  - mandatory reason;
  - original-content snapshot; and
  - deletion timestamp.

### Phase Decision

No matching service was switched to `profiles` during Phase 1. This was an
intentional migration-only phase. The runtime read path was migrated safely in
Phase 7 after profile location editing and combined provider matching existed.

### Verification

The migration was reviewed before later behavior was built against it and was
applied manually through pgAdmin Query Tool.

---

## 4. Phase 2 - Provider Resources Backend

### Goal

Create a real backend contract through which volunteers and organizations can
describe what help they can provide and through which volunteers can store their
occupation.

### Backend Components Added

#### Provider resource model

```text
src/main/java/com/humanitarian/platform/model/ProviderResource.java
src/main/java/com/humanitarian/platform/repository/ProviderResourceRepository.java
src/main/java/com/humanitarian/platform/dto/ProviderResourceDto.java
src/main/java/com/humanitarian/platform/dto/ProviderResourceResponse.java
src/main/java/com/humanitarian/platform/service/ProviderResourceService.java
src/main/java/com/humanitarian/platform/controller/ProviderResourceController.java
```

#### Resource API

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `GET` | `/api/provider-resources/me` | Volunteer, organization | Lists the current provider's resources |
| `PUT` | `/api/provider-resources` | Volunteer, organization | Creates or updates one help-type resource |
| `DELETE` | `/api/provider-resources/{helpType}` | Volunteer, organization | Deletes the current provider's row for that help type |

The service repeats the role check instead of relying only on the controller.
`BENEFICIARY`, `PSYCHOLOGIST`, and `ADMIN` users cannot manage provider resources.

#### Validation implemented

- Supported help types:
  - `MEDICAL`
  - `FOOD`
  - `SHELTER`
  - `WATER`
  - `CLOTHING`
- `NUMERIC` requires `capacityAmount > 0`.
- `QUALITATIVE` requires a nonblank `capacityLabel` no longer than 50 characters.
- Help types and modes are normalized before persistence.
- Upsert reuses the existing `(user_id, help_type)` row instead of inserting a
  duplicate.

Example numeric payload:

```json
{
  "helpType": "FOOD",
  "capacityMode": "NUMERIC",
  "capacityAmount": 25,
  "capacityLabel": null
}
```

Example qualitative payload:

```json
{
  "helpType": "SHELTER",
  "capacityMode": "QUALITATIVE",
  "capacityAmount": null,
  "capacityLabel": "Limited temporary accommodation"
}
```

#### Shared help-type normalization

Added:

```text
src/main/java/com/humanitarian/platform/util/HelpTypeNormalizer.java
```

This removed duplicated normalization behavior and gave request creation,
resource persistence, and matching one convention.

#### Volunteer occupation

Added:

```text
src/main/java/com/humanitarian/platform/dto/VolunteerOccupationDto.java
src/main/java/com/humanitarian/platform/service/VolunteerService.java
src/main/java/com/humanitarian/platform/controller/VolunteerController.java
```

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `GET` | `/api/volunteers/me/occupation` | Volunteer | Reads the current occupation |
| `PUT` | `/api/volunteers/me/occupation` | Volunteer | Updates the occupation |

### Postman Requests Added

```text
Get My Provider Resources.request.yaml
Upsert Provider Resource.request.yaml
Delete Provider Resource.request.yaml
Get Volunteer Occupation.request.yaml
Update Volunteer Occupation.request.yaml
```

### Tests Added

`ProviderResourceServiceTest` covers:

- valid numeric capacity;
- valid qualitative capacity;
- missing numeric amount;
- wrong-role rejection;
- true upsert behavior;
- exclusion of zero or malformed capacity; and
- manual eligibility rejection.

`VolunteerServiceTest` covers occupation reads and updates for the correct and
incorrect roles.

---

## 5. Phase 3 - Provider Resources Frontend

### Goal

Expose the Phase 2 backend through server-backed UI instead of adding more
localStorage-only profile fields.

### Settings Page

The editable provider section was placed in `settings.html`, because that is the
server-backed account page.

Added a **My Services** section for `VOLUNTEER` and `ORGANIZATION` users with one
row for each supported help type:

- Medical
- Food
- Water
- Shelter
- Clothing

Each row supports:

- an enable/disable toggle;
- numeric capacity mode;
- qualitative description mode;
- current-value loading from the backend;
- upsert through `PUT /api/provider-resources`; and
- deletion through `DELETE /api/provider-resources/{helpType}`.

Volunteers also receive an Occupation field connected to the occupation API.
Organizations do not see that field.

### Profile Page

`profile.html` now loads and displays provider information read-only:

- volunteer occupation;
- enabled resource types; and
- capacity descriptions.

### Dashboard Prompt

For a volunteer or organization with no `provider_resources` rows,
`dashboard.html` displays a dismissible prompt:

```text
Complete your profile so beneficiaries can be matched to you
```

The prompt links to the existing My Services settings section instead of opening
a second questionnaire.

### Main Files Updated

```text
src/main/resources/static/settings.html
src/main/resources/static/profile.html
src/main/resources/static/dashboard.html
```

### Important Decision

Editable provider data lives on the server-backed settings page. The profile page
is a display surface; it is not a second competing source of truth.

---

## 6. Phase 4 - Resource-Aware Matching

### Goal

Prevent the nearest provider from being suggested or assigned when that provider
does not actually list the requested type of help.

### Eligibility Logic Added

`ProviderResourceService.findEligibleProviderUserIds(helpType)` now returns users
whose resource row is usable:

- numeric capacity must be greater than zero;
- qualitative capacity must have a nonblank label; and
- the help type must be supported and normalized.

`ProviderResourceService.requireUsableResource(userId, helpType)` enforces the
same rule for manual acceptance.

The post-seven-phase capacity update added
`findEligibleProviderCapacityAssessments(helpType, peopleCount)` and a request-aware
`requireUsableResource` overload. Each usable provider now carries:

- `capacitySufficient: true` when numeric capacity covers `peopleCount`;
- `capacitySufficient: false` when positive numeric capacity is below
  `peopleCount`; or
- `capacitySufficient: null` for qualitative capacity or an unavailable comparison.

All three states remain eligible.

### Automatic Assignment

The automatic material-request flow was changed to:

1. Normalize the request's help type.
2. Load eligible provider user IDs from `provider_resources`.
3. Remove volunteers who do not have a usable matching resource.
4. Distance-sort only the remaining volunteers.
5. Atomically claim the nearest remaining volunteer.
6. Conditionally change the request from `PENDING` to `ASSIGNED`.
7. Record the assignment as `AUTO_GEO`.

At this phase, organizations were confirmed to be manual-only, so organization
automatic matching was not invented prematurely. Combined automatic organization
matching was added later in Phase 7.

### Manual Assignment

Both volunteers and organizations must pass the resource check before
`PUT /api/help-requests/{id}/assign` can assign them.

### Ranked Queue

Nearest-volunteer suggestions in the ranked queue use the same resource filter,
so a closer volunteer without the correct resource is not suggested. Ranked
responses now also expose `capacityMode`, `capacityAmount`, and
`capacitySufficient`; the admin request table and detail modal render the result.

### Psychological Requests

Psychological crisis routing was intentionally left unchanged. Psychologists do
not use `provider_resources`.

### Main Files Updated

```text
src/main/java/com/humanitarian/platform/service/ProviderResourceService.java
src/main/java/com/humanitarian/platform/service/AutomaticAssignmentService.java
src/main/java/com/humanitarian/platform/service/HelpRequestService.java
```

### Tests Added

- A closer volunteer with the wrong resource is skipped for a farther eligible
  volunteer.
- A manual organization without the requested resource is rejected.
- Ranked suggestions exclude providers with the wrong resource.
- Zero or malformed capacities are not eligible.
- A numeric shortfall is reported as insufficient but remains eligible for nearest
  automatic assignment.
- Covering numeric capacity is reported as sufficient.
- Qualitative capacity is reported as unknown rather than insufficient.

### Remaining Capacity Boundary

Positive numeric capacity is compared with `HelpRequest.peopleCount` for ranked and
admin visibility. The comparison does not reorder automatic candidates or block
manual acceptance, so partial contributions remain possible. The implementation
still does not decrement inventory after assignment or reserve capacity
transactionally.

---

## 7. Phase 5 - Protected Contact Reveal

### Goal

Allow the beneficiary and their assigned provider to contact each other while
preventing unrelated users from accessing personal information and preserving
psychological-request anonymity.

### Backend Added

```text
src/main/java/com/humanitarian/platform/dto/ContactInfoResponse.java
src/main/java/com/humanitarian/platform/service/ContactInfoService.java
```

### Material Contact Endpoint

| Method | Endpoint | Access |
|---|---|---|
| `GET` | `/api/help-requests/{id}/contact` | Beneficiary, assigned volunteer, assigned organization |

Behavior:

- the assigned volunteer can see the beneficiary's contact;
- the assigned organization can see the beneficiary's contact;
- the beneficiary can see the assigned volunteer or organization contact;
- an unrelated provider receives `403 Forbidden`; and
- contact is not exposed before a valid assignment relationship exists.

### Psychological Contact Endpoint

| Method | Endpoint | Access |
|---|---|---|
| `GET` | `/api/psychological-requests/{id}/contact` | Requesting beneficiary, assigned psychologist |

Behavior:

- an assigned psychologist can see a non-anonymous beneficiary's contact;
- the requesting beneficiary can see the assigned psychologist's contact;
- an unrelated psychologist receives `403 Forbidden`; and
- an anonymous request never reveals beneficiary identity, email, or phone to
  the psychologist.

### Frontend Updated

`help-requests.html` now provides contact controls for:

- assigned volunteers;
- assigned organizations; and
- beneficiaries viewing the assigned responder.

`psychological.html` now provides contact controls for:

- assigned psychologists; and
- beneficiaries viewing their assigned psychologist.

Anonymous psychological cases display protected anonymous messaging instead of
cached or fallback identity data.

### Tests Added

`ContactInfoServiceTest` and `ContactControllerTest` cover:

- unrelated-user rejection;
- volunteer contact access;
- organization contact access in both directions;
- psychologist contact access in both directions;
- anonymous psychological masking; and
- controller-level `403` and anonymous response contracts.

---

## 8. Phase 6 - Community Backend And Moderation

### Goal

Replace the browser-local shared feed with a real multi-user backend and add
accountable administrator moderation.

### V4 Message-Type Migration

File:

```text
database/migrations/V4__message_types_and_community_channel.sql
```

The migration added:

- mandatory `message_type` with no default;
- nullable `community_category`;
- conditional `receiver_id` nullability; and
- database constraints separating direct and community messages.

Database invariant:

```text
DIRECT    -> receiver_id must be present, community_category must be null
COMMUNITY -> receiver_id must be null
```

The table contained zero rows when V4 was applied, so no backfill was required.

### Message Model And Repository

Added or updated:

```text
src/main/java/com/humanitarian/platform/model/MessageType.java
src/main/java/com/humanitarian/platform/model/Message.java
src/main/java/com/humanitarian/platform/model/MessageDeletion.java
src/main/java/com/humanitarian/platform/repository/MessageRepository.java
src/main/java/com/humanitarian/platform/repository/MessageDeletionRepository.java
```

Community queries explicitly filter:

```text
message_type = COMMUNITY
is_deleted = false
```

Direct-message repository methods explicitly filter `DIRECT`, preventing private
messages from leaking into the community feed.

### Community API

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `GET` | `/api/community/messages?page=0&size=20` | Volunteer, psychologist, organization, admin | Lists visible community messages |
| `POST` | `/api/community/messages` | Volunteer, psychologist, organization, admin | Creates an explicit community message with no receiver |
| `DELETE` | `/api/community/messages/{id}?reason=...` | Admin | Soft-deletes with a mandatory reason |
| `GET` | `/api/admin/community/deletions?page=0&size=20` | Admin | Lists moderation audit entries |

Administrators were intentionally allowed to post community updates. Beneficiaries
are rejected at both controller and service layers.

### Existing Community Categories Preserved

Because `community.html` already contained a category concept, the existing
taxonomy was normalized instead of replacing it with an invented `GENERAL` picker:

- `UPDATE`
- `SUCCESS_STORIES`
- `QUESTION`
- `TIPS_ADVICE`
- `EVENTS`
- `RESOURCES`
- `GRATITUDE`
- `INFO`

Missing categories fall back to `UPDATE`. Unknown categories are rejected.

### Moderation Behavior

Administrator deletion is transactional:

1. Load a visible community message.
2. Reject a missing or blank reason.
3. Set `messages.is_deleted = true`.
4. Insert `message_deletions` with the original author and content snapshot.
5. Exclude the message from future community-feed reads.
6. Keep the audit entry available to administrators.

### Frontend Updated

`community.html` now:

- loads paginated messages from the backend;
- creates posts through the backend;
- renders server-provided author identity and role;
- lets administrators open a moderation control;
- requires an inline justification before deletion; and
- refreshes feed state after successful moderation.

Likes and comments remain browser-local. Community image uploads remain deferred
because there is no backend media-storage contract for them.

### Tests Added

`MessageServiceTest` and `MessageControllerTest` cover:

- beneficiary GET/POST rejection;
- non-admin delete rejection;
- blank-reason rejection;
- successful soft delete;
- audit snapshot creation;
- deleted-message exclusion;
- audit-log retrieval; and
- proof that a direct message is never returned by the community API.

### Documentation Added

```text
docs/BACKEND_GUIDE.md
docs/FRONTEND_GUIDE.md
```

The guides describe architecture, role boundaries, migrations, community behavior,
contact reveal, frontend data ownership, testing, and extension patterns.

---

## 9. Phase 7 - Availability, Location, And Combined Geo-Matching

### Goal

Make organizations full participants in automatic geographic matching while using
one canonical location source and preserving a provider's manual availability
preference across assignment claims.

### Corrected Assumptions

Repository inspection showed that:

- volunteers had an effective `is_available` claim flag but no manual control;
- organizations had no availability flag;
- no role had a server-backed matching-location UI; and
- organization automatic assignment did not exist.

The implementation therefore added the missing behavior symmetrically instead of
building organization-only controls.

### V5 Migration

File:

```text
database/migrations/V5__provider_availability_preference.sql
```

Added:

- `organizations.is_available BOOLEAN NOT NULL DEFAULT true`;
- `organizations.availability_preference BOOLEAN NOT NULL DEFAULT true`;
- `volunteers.availability_preference BOOLEAN NOT NULL DEFAULT true`;
- provider availability indexes; and
- explanatory database comments.

`is_available` is the effective claim state. `availability_preference` is the
provider's saved choice and is restored after the final active assignment ends.

V5 uses `IF NOT EXISTS`. Re-running it safely produced PostgreSQL notices that the
columns already existed, followed by `COMMIT`; those notices were expected.

### Shared Availability API

Added:

```text
src/main/java/com/humanitarian/platform/dto/ProviderAvailabilityDto.java
src/main/java/com/humanitarian/platform/dto/ProviderAvailabilityResponse.java
src/main/java/com/humanitarian/platform/service/ProviderAvailabilityService.java
src/main/java/com/humanitarian/platform/controller/ProviderAvailabilityController.java
```

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `GET` | `/api/provider-availability/me` | Volunteer, organization | Returns effective state, preference, provider ID, and active assignment count |
| `PUT` | `/api/provider-availability/me` | Volunteer, organization | Updates the current provider's own availability |

Example request:

```json
{
  "available": false
}
```

Rules:

- other roles receive `403 Forbidden`;
- no user can change another provider's state;
- setting false during an active assignment leaves the assignment intact and
  records the future preference;
- setting true while claimed is allowed and reopens the provider for new matches;
  and
- claim release restores `availability_preference` instead of blindly forcing
  true.

### Server-Backed Matching Location

Updated:

```text
src/main/java/com/humanitarian/platform/dto/UserProfileDto.java
src/main/java/com/humanitarian/platform/service/UserService.java
src/main/java/com/humanitarian/platform/controller/UserController.java
```

| Method | Endpoint | Access | Behavior |
|---|---|---|---|
| `GET` | `/api/users/me/profile` | Authenticated | Loads account/profile fields and saved coordinates |
| `PUT` | `/api/users/me/profile` | Authenticated | Saves profile fields and matching coordinates |

Coordinate validation:

- latitude must be between `-90` and `90`;
- longitude must be between `-180` and `180`; and
- latitude and longitude must be supplied together.

### Settings UI

`settings.html` now shows **Matching Location** for:

- beneficiaries;
- volunteers; and
- organizations.

It is hidden for psychologists because psychological routing is duty/crisis-based,
not distance-based.

The location UI provides:

- current saved address and coordinates;
- manual latitude and longitude fields;
- a browser-geolocation convenience button;
- explicit Save Location behavior; and
- saved/not-saved feedback.

Browser geolocation only fills the visible fields. It does not write location in
the background; the user must explicitly save.

The My Services area now also contains the provider availability control. The
toggle represents the saved preference. When an active claim temporarily makes a
provider unavailable while their preference remains on, an **Available Now**
command lets them explicitly reopen themselves.

### Canonical Location Read Path

`GeoMatchingService` now reads provider coordinates only through:

```text
Volunteer/Organization -> User -> Profile -> latitude/longitude
```

No matching service reads `volunteers.latitude` or `volunteers.longitude` anymore.
Those legacy columns remain in the schema only for compatibility and future
backfill/removal work.

### Combined Automatic Candidate Pool

For a material request containing coordinates, the final automatic flow is:

1. Normalize the request help type.
2. Load provider user IDs with usable matching resources.
3. Load available volunteers.
4. Load available organizations.
5. Filter both groups by resource eligibility.
6. Remove providers without profile coordinates.
7. Build one provider-neutral candidate list.
8. Calculate Haversine distance for every candidate.
9. Sort the combined list from nearest to farthest.
10. Atomically claim the first candidate still available.
11. Conditionally update the request from `PENDING` to `ASSIGNED`.
12. Save an `AUTO_GEO` assignment with either `volunteer_id` or
    `organization_id`.
13. Release the provider if the request update loses a concurrent race.

The algorithm has no volunteer-first rule. Distance is the primary selector after
resource and availability filtering. Exactly equal distances are resolved
deterministically by provider type name and then provider ID.

### Manual Assignment Improvements

- Organizations now use the same atomic claim pattern as volunteers.
- A volunteer or organization claim is released if manual request assignment
  loses a race.
- Completing or cancelling an assignment releases either provider type only when
  no other active assignment remains.
- Release restores the provider's saved preference.

### Evaluation And Ranked Matching

- Existing volunteer ranked suggestions now use canonical profile coordinates.
- Multi-objective distance calculations use the same profile-backed geo service.
- Production automatic assignment now compares both provider roles.

### Tests Added Or Expanded

Phase 7 coverage proves:

- an organization closer than a volunteer wins;
- a volunteer closer than an organization wins;
- an organization without the requested resource is skipped;
- an unavailable organization is skipped;
- an organization is released after a lost assignment race;
- manual volunteer and organization claims are released after lost races;
- profile coordinates override legacy volunteer coordinates;
- volunteers and organizations can manage availability;
- a beneficiary cannot manage provider availability;
- false and true availability changes preserve active assignments;
- organization location save/load uses `profiles`; and
- incomplete coordinate pairs are rejected before persistence.

---

## 10. Final API Surface Added By The Seven Phases

| Method | Endpoint | Main roles | Phase |
|---|---|---|---:|
| `GET` | `/api/provider-resources/me` | Volunteer, organization | 2 |
| `PUT` | `/api/provider-resources` | Volunteer, organization | 2 |
| `DELETE` | `/api/provider-resources/{helpType}` | Volunteer, organization | 2 |
| `GET` | `/api/volunteers/me/occupation` | Volunteer | 2 |
| `PUT` | `/api/volunteers/me/occupation` | Volunteer | 2 |
| `GET` | `/api/help-requests/{id}/contact` | Requester, assigned material provider | 5 |
| `GET` | `/api/psychological-requests/{id}/contact` | Requester, assigned psychologist | 5 |
| `GET` | `/api/community/messages` | Responder roles, admin | 6 |
| `POST` | `/api/community/messages` | Responder roles, admin | 6 |
| `DELETE` | `/api/community/messages/{id}` | Admin | 6 |
| `GET` | `/api/admin/community/deletions` | Admin | 6 |
| `GET` | `/api/provider-availability/me` | Volunteer, organization | 7 |
| `PUT` | `/api/provider-availability/me` | Volunteer, organization | 7 |
| `GET` | `/api/users/me/profile` | Authenticated | 7 |
| `PUT` | `/api/users/me/profile` | Authenticated | Extended in Phase 7 |

Existing request-assignment endpoints were also strengthened by resource,
availability, ownership, anonymity, and race-condition rules.

---

## 11. Database Migration Order

The application uses:

```properties
spring.jpa.hibernate.ddl-auto=none
```

Therefore migrations are manual and must follow filename order after the existing
base schema:

```text
V2__matching_and_assignment_history.sql
V3__location_resources_and_message_moderation.sql
V4__message_types_and_community_channel.sql
V5__provider_availability_preference.sql
```

PowerShell example:

```powershell
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" `
  -h 127.0.0.1 -U postgres -d Web_DB `
  -f ".\database\migrations\V3__location_resources_and_message_moderation.sql"

& "C:\Program Files\PostgreSQL\17\bin\psql.exe" `
  -h 127.0.0.1 -U postgres -d Web_DB `
  -f ".\database\migrations\V4__message_types_and_community_channel.sql"

& "C:\Program Files\PostgreSQL\17\bin\psql.exe" `
  -h 127.0.0.1 -U postgres -d Web_DB `
  -f ".\database\migrations\V5__provider_availability_preference.sql"
```

Important:

- V4 has no `message_type` default and was designed for the confirmed empty
  `messages` table. It should not be rerun after successful application.
- V5 uses `IF NOT EXISTS` and can safely report already-exists notices.
- The repository still does not contain a complete V1 bootstrap migration or an
  automatic Flyway/Liquibase runner.

---

## 12. Verification Performed

### Java

```powershell
.\mvnw.cmd -q test
```

Final result:

```text
81 tests passed
0 failures
0 errors
0 skipped
```

Coverage includes:

- provider resource validation and upsert;
- tri-state provider-capacity assessment against request people count;
- occupation authorization;
- resource-aware automatic and manual matching;
- weighted and geographic matching behavior;
- organization and volunteer atomic claims;
- assignment race cleanup;
- provider availability behavior;
- profile location validation and persistence;
- contact ownership and anonymity;
- community role enforcement;
- direct/community message isolation;
- mandatory moderation reasons;
- moderation audit snapshots;
- assignment lifecycle and history; and
- Spring application-context startup.

### Frontend

- `settings.html` inline JavaScript passed syntax validation.
- Role-based visibility was implemented for resources, occupation, matching
  location, and availability.
- All frontend writes wait for a successful API response before treating data as
  saved.

### Database

- V3, V4, and V5 were applied manually during the work.
- PostgreSQL accepted V5 and completed the transaction.
- The Spring context successfully initialized all JPA entities and repositories
  against the migrated schema.

---

## 13. Key Design Decisions

1. **Profile location is canonical.** Organizations did not receive duplicate
   latitude/longitude columns.
2. **Provider resources are structured.** Matching does not infer resources from
   free-form skills or organization descriptions.
3. **Settings is the editable server-backed profile surface.** Local-only profile
   fields were not extended as a competing data source.
4. **Resource filtering precedes distance sorting.** Being close is irrelevant if
   the provider cannot supply the requested help type.
5. **Psychological routing remains separate.** It uses crisis detection and
   psychologist duty/load, not material resources or distance.
6. **Community and direct messages share storage but not visibility.** Both Java
   queries and PostgreSQL constraints enforce the distinction.
7. **Moderation is auditable.** A required reason and content snapshot are stored.
8. **Effective availability and preference are separate.** Assignment claims do
   not erase the provider's manual choice.
9. **Automatic provider selection is role-neutral.** The nearest eligible
   volunteer or organization wins.
10. **Race cleanup is explicit.** A successful provider claim is released if the
    request can no longer be assigned.

---

## 14. Current Known Boundaries

The seven phases are complete, but these intentional boundaries remain:

- No complete V1 bootstrap migration exists.
- Migrations are not run automatically.
- Numeric capacity is compared with `peopleCount` and exposed in ranked/admin
  responses, but it is not decremented or reserved transactionally.
- Haversine distance is straight-line distance, not driving distance or travel
  time.
- A provider without `profiles.latitude/longitude` is excluded even if legacy
  volunteer coordinates exist.
- The legacy volunteer coordinate columns remain until a separate backfill/removal
  migration is planned.
- Automatic matching requires coordinates on the material request itself; saving
  beneficiary profile coordinates does not geocode or silently rewrite a request.
- Ranked-queue suggestion fields retain the existing volunteer-oriented response
  contract, while production automatic assignment is combined-role.
- Direct-message controllers are still not an active user workflow, although
  direct rows remain supported and isolated in the shared table.
- Community likes and comments remain local browser interactions.
- Community photo upload/storage is not implemented.
- Existing message-deletion foreign keys mean physical message purging requires a
  future retention-policy migration.

---

## 15. Documentation Produced Or Updated

```text
README.md
database/migrations/README.md
docs/BACKEND_GUIDE.md
docs/FRONTEND_GUIDE.md
NIDAA_7_PHASES_IMPLEMENTATION_REPORT.md
```

The root README now reflects combined provider assignment, profile-based location,
availability endpoints, migration V5, tests, and current limitations.

---

## 16. Local Git Status When This Report Was Written

Branch:

```text
improvment
```

Local implementation commits after `origin/improvment`:

| Commit | Main phase content |
|---|---|
| `3cd65fb` | Phase 1 schema plus Phase 2 provider-resource/occupation backend |
| `be173f1` | Phase 3 provider frontend |
| `7b976bc` | Phase 4 resource-aware matching |
| `090d32f` | Phase 5 contact reveal plus Phase 6 community/V4 |
| `1f5e558` | Phase 7 availability, profile location, and combined geo-matching |

At report creation, the local branch was five commits ahead of
`origin/improvment`. The implementation commits were local and had not yet been
pushed. `docs/BACKEND_GUIDE.md`, `docs/FRONTEND_GUIDE.md`, and this report still
needed to be added to source control.

---

## 17. Final Outcome

Nidaa now supports an end-to-end material-help workflow in which:

1. providers declare structured services;
2. providers save a matching location and availability preference;
3. beneficiaries submit requests with urgency, vulnerability, people count, and
   coordinates;
4. the backend filters providers by resource and availability and reports whether
   the suggested provider's numeric capacity covers the request;
5. volunteers and organizations compete in one distance-ranked pool;
6. the winner is claimed atomically and recorded in assignment history;
7. assigned parties can securely reveal contact details; and
8. responders can coordinate through a server-backed, moderated community feed.

Psychological support remains intentionally separate, with crisis detection,
on-duty psychologist routing, assignment lifecycle behavior, protected contact
exchange, and strict anonymity for anonymous requests.
