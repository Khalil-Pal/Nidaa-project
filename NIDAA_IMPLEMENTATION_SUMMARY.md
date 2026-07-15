# Nidaa Implementation Summary

## Overview

This document summarizes the implementation work completed for the Nidaa humanitarian aid platform.

The work focused on activating previously unused schema fields and services:

- Help request priority scoring
- Scheduled priority recalculation
- Psychological crisis detection
- Volunteer geo-matching
- Assignment table wiring
- Ranked request queues
- Matching strategy evaluation
- Admin ranked dashboard data
- Dev-only sample data seeding
- Business logic tests

## Backend Changes

### Priority Scoring

Added `PriorityScoreService`.

It calculates a score for each `HelpRequest` using:

- Urgency level
- Children, elderly, and disabled vulnerability flags
- People count
- Waiting time since creation

Updated `HelpRequestService.createRequest()` so new help requests now receive a real `priorityScore` before saving.

### Scheduled Priority Recalculation

Added `PriorityScoreScheduler`.

It recalculates scores for all pending help requests every 30 minutes.

Enabled scheduling in `PlatformApplication` with `@EnableScheduling`.

### Crisis Detection

Added `CrisisDetectorService`.

Updated `PsychologicalRequestService.createRequest()` so `isCrisis` is no longer hardcoded to `false`.

Crisis is detected when:

- Category is `CRISIS_SUPPORT`
- Description contains crisis-related keywords

When a crisis is detected, `crisisDetectedAt` is also set.

### Geo Matching

Added `GeoMatchingService`.

It provides:

- Haversine distance calculation
- Nearest available volunteer selection
- Filtering by `Volunteer.isAvailable = true`

Added `latitude` and `longitude` fields to `Volunteer`.

### Assignment Table Wiring

Updated `HelpRequestService.assignToMe()` so successful help-request assignment writes an `Assignment` record.

Updated `PsychologicalRequestService.acceptRequest()` so accepted psychological requests also write an `Assignment` record.

Updated help and psychological status flows so when a request becomes `COMPLETED` or `CANCELLED`, the related assignment status is updated too.

Updated `AssignmentRepository` with latest-assignment lookup methods.

Adjusted `Assignment.volunteerId` to allow null so organization assignments can also be stored.

### On-Duty Psychologist Rule

Updated `PsychologicalRequestService.acceptRequest()`.

Crisis psychological requests now require the accepting psychologist to have `isOnDuty = true`.

Added `findByIsOnDutyTrue()` to `PsychologistRepository`.

### Ranked Queue API

Added `RankedRequestDTO`.

Added `HelpRequestService.getRankedWithSuggestions()`.

Added new v1 endpoint:

```text
GET /api/v1/help-requests/ranked
```

Allowed roles:

- `ADMIN`
- `VOLUNTEER`
- `ORGANIZATION`

### Matching Evaluation API

Added matching strategy infrastructure:

- `MatchingStrategy`
- `FifoMatchingStrategy`
- `WeightedScoringStrategy`
- `MatchingEvaluationService`

Added new v1 endpoint:

```text
GET /api/v1/admin/evaluation
```

Allowed role:

- `ADMIN`

### Ranked Admin Dashboard API

Added new v1 endpoint:

```text
GET /api/v1/admin/dashboard/ranked
```

It returns:

- Ranked material help requests
- Crisis psychological cases
- Total pending material requests
- Total crisis psychological requests

Allowed role:

- `ADMIN`

### Security

Updated `SecurityConfig` to protect the new `/api/v1/**` endpoints by role.

Existing `/api/**` endpoints were not renamed or moved.

### Dev Data Seeder

Added `DataSeeder`, active only under the `dev` profile.

It seeds 500 sample help requests when:

- `dev` profile is active
- The help request table is empty
- At least one user exists

Added `application-dev.properties`.

## Frontend Changes

Updated `src/main/resources/static/admin-requests.html`.

The admin requests page now fetches:

```text
/api/v1/admin/dashboard/ranked
```

Added UI support for:

- Priority score column
- Crisis indicator column
- Suggested volunteer column
- Distance display when coordinates are available
- Psychological crisis cases in the same admin view

## Tests Added

Added priority scoring tests:

- Critical requests with vulnerabilities score high
- Low urgency requests score low
- Waiting time increases score

Added crisis detection tests:

- `CRISIS_SUPPORT` category is detected
- Crisis keyword is detected
- Normal case is not detected

Added geo matching tests:

- Moscow to Saint Petersburg distance is approximately 700 km
- Same point distance is zero
- Unavailable volunteers are ignored

Added request lifecycle test:

- Create help request
- Assign to volunteer
- Write assignment record
- Complete request
- Update assignment status

## Build Tool Fix

Fixed `mvnw.cmd`.

The wrapper previously failed on this Windows environment with:

```text
Cannot index into a null array
Cannot start maven from wrapper
```

The wrapper now safely handles a normal, non-symlink `.m2` directory.

## Verification

The following commands were run successfully:

```text
cmd /c mvnw.cmd clean compile
```

Result:

```text
BUILD SUCCESS
```

```text
cmd /c mvnw.cmd test
```

Result:

```text
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Important Database Note

The project uses:

```properties
spring.jpa.hibernate.ddl-auto=none
```

Because of that, Hibernate will not automatically update the PostgreSQL schema.

If the database does not already contain these columns, they must be added manually:

```sql
ALTER TABLE volunteers ADD COLUMN latitude numeric;
ALTER TABLE volunteers ADD COLUMN longitude numeric;
```

## Main Files Added

- `src/main/java/com/humanitarian/platform/service/PriorityScoreService.java`
- `src/main/java/com/humanitarian/platform/service/PriorityScoreScheduler.java`
- `src/main/java/com/humanitarian/platform/service/CrisisDetectorService.java`
- `src/main/java/com/humanitarian/platform/service/GeoMatchingService.java`
- `src/main/java/com/humanitarian/platform/service/MatchingEvaluationService.java`
- `src/main/java/com/humanitarian/platform/service/matching/MatchingStrategy.java`
- `src/main/java/com/humanitarian/platform/service/matching/FifoMatchingStrategy.java`
- `src/main/java/com/humanitarian/platform/service/matching/WeightedScoringStrategy.java`
- `src/main/java/com/humanitarian/platform/dto/RankedRequestDTO.java`
- `src/main/java/com/humanitarian/platform/controller/HelpRequestV1Controller.java`
- `src/main/java/com/humanitarian/platform/controller/AdminV1Controller.java`
- `src/main/java/com/humanitarian/platform/config/DataSeeder.java`
- `src/main/resources/application-dev.properties`
- `src/test/java/com/humanitarian/platform/service/PriorityScoreServiceTest.java`
- `src/test/java/com/humanitarian/platform/service/CrisisDetectorServiceTest.java`
- `src/test/java/com/humanitarian/platform/service/GeoMatchingServiceTest.java`
- `src/test/java/com/humanitarian/platform/integration/RequestLifecycleTest.java`

## Main Files Modified

- `mvnw.cmd`
- `src/main/java/com/humanitarian/platform/PlatformApplication.java`
- `src/main/java/com/humanitarian/platform/config/SecurityConfig.java`
- `src/main/java/com/humanitarian/platform/model/Assignment.java`
- `src/main/java/com/humanitarian/platform/model/Volunteer.java`
- `src/main/java/com/humanitarian/platform/repository/AssignmentRepository.java`
- `src/main/java/com/humanitarian/platform/repository/PsychologistRepository.java`
- `src/main/java/com/humanitarian/platform/repository/VolunteerRepository.java`
- `src/main/java/com/humanitarian/platform/service/HelpRequestService.java`
- `src/main/java/com/humanitarian/platform/service/PsychologicalRequestService.java`
- `src/main/resources/static/admin-requests.html`
