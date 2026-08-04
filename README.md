<p align="center">
  <img src="src/main/resources/static/nidaa-logo-nobg.jpg" alt="Nidaa logo" width="180">
</p>

<h1 align="center">Nidaa</h1>

<p align="center">
  A humanitarian assistance coordination platform that connects people in need with volunteers,
  organizations, psychologists, and platform administrators.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-3.2.0-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.2.0">
  <img src="https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white" alt="PostgreSQL 17">
  <img src="https://img.shields.io/badge/Auth-JWT-111827" alt="JWT authentication">
  <img src="https://img.shields.io/badge/Status-Active_Development-F59E0B" alt="Active development">
</p>

## Overview

Nidaa provides a single place for beneficiaries to request material or psychological support and for verified responders to manage those requests. The platform combines role-based workflows with weighted priority scoring, geographic volunteer matching, crisis detection, assignment history, and strategy evaluation metrics.

The backend is a Spring Boot REST application. The frontend is a set of responsive static HTML, CSS, and JavaScript pages served directly by Spring Boot.

Repository: [github.com/Khalil-Pal/Nidaa-project](https://github.com/Khalil-Pal/Nidaa-project)

## Detailed Documentation

- [Backend guide](docs/BACKEND_GUIDE.md): startup, configuration, security, database model, APIs, request lifecycles, matching, migrations, and tests.
- [Frontend guide](docs/FRONTEND_GUIDE.md): pages, role navigation, browser state, API calls, shared layout, local-only features, debugging, and test checklists.

## Core Capabilities

### Identity and access

- Email-based registration verification.
- Stateless JWT access tokens and stored refresh tokens.
- Role-based authorization with Spring Security.
- Password reset and password-change verification by email.
- Login-attempt lockout protection.
- Administrator approval for volunteer, psychologist, and organization accounts.

### Material help requests

- Submit and track food, medical, shelter, water, clothing, and other requests.
- Capture urgency, number of people, vulnerability flags, address, and coordinates.
- Calculate weighted priority from urgency, children, elderly people, disabled people, group size, and waiting time.
- Rank pending requests by priority.
- Automatically assign the nearest available volunteer or organization that lists the requested resource when coordinates exist.
- Allow volunteers and organizations to accept pending requests manually.
- Enforce request lifecycle transitions from `PENDING` to `ASSIGNED`, then `COMPLETED` or `CANCELLED`.

### Psychological support

- Submit confidential or anonymous psychological support requests.
- Detect crisis cases from the selected category and description keywords.
- Score psychological requests using urgency, crisis status, and waiting time.
- Route crisis cases to verified, on-duty psychologists.
- Balance crisis routing by active workload, rating, experience, and stable ID ordering.
- Allow psychologists to accept and update assigned cases.

### Operations and analytics

- Record material and psychological assignment history.
- Distinguish manual, geographic, crisis, and administrator assignment sources.
- Compare FIFO, weighted-scoring, and multi-objective matching strategies.
- Report urgent waiting time, urgent coverage, volunteer utilization, regional fairness, completion performance, and travel distance.
- Provide administrator dashboards for users, approvals, requests, stories, ranked queues, and evaluation results.

## User Roles

| Role | Main responsibilities |
|---|---|
| `BENEFICIARY` | Submit and track help requests, request psychological support, and manage a personal profile. |
| `VOLUNTEER` | Browse material requests, view ranked suggestions, accept work, and update request status. |
| `PSYCHOLOGIST` | Review pending support requests, accept cases, and handle crisis assignments while on duty. |
| `ORGANIZATION` | Coordinate and accept material help requests on behalf of an aid organization. |
| `ADMIN` | Approve responders, manage users and requests, inspect assignment history, and evaluate matching strategies. |

## How Matching Works

### Request priority

Priority determines which request should be reviewed first. A higher score means higher operational priority.

Material requests receive points for:

| Factor | Points |
|---|---:|
| Urgency | 10 to 40 |
| Children present | +10 |
| Elderly people present | +10 |
| Disabled people present | +15 |
| Number of people | +2 each, capped at +20 |
| Waiting time | +1 per two full hours |

Psychological requests receive urgency points, a `+35` crisis bonus, and the same waiting-time growth.

### Geographic assignment

When a material request includes latitude and longitude, the production assignment flow:

1. Loads `provider_resources` rows for the request's normalized `helpType`.
2. Removes numeric rows whose `capacityAmount` is zero and malformed qualitative rows.
3. Compares positive numeric capacity with `peopleCount` for the ranked/admin capacity flag without excluding partial-capacity providers.
4. Loads available volunteers and organizations and keeps only providers whose user ID is in the eligible resource set.
5. Reads provider coordinates from `profiles` and removes providers without both values.
6. Calculates straight-line distance with the Haversine formula.
7. Orders candidates from nearest to farthest.
8. Atomically claims the first available provider, regardless of provider type.
9. Changes the request from `PENDING` to `ASSIGNED`.
9. Saves an `AUTO_GEO` assignment-history record with the distance.

The atomic claim prevents two simultaneous requests from assigning the same provider. When an assignment is completed or cancelled, the provider is released to their saved availability preference if no other active assignment remains.

Volunteers and organizations that manually accept a request must also list a usable resource for that request's help type. Ranked-queue volunteer suggestions use the same resource filter and canonical profile coordinates.

### Crisis routing

Crisis psychological requests are routed separately. The system considers verified, on-duty psychologists and orders them by:

1. Lowest number of active assignments.
2. Highest rating.
3. Most experience.
4. Lowest stable psychologist ID as a final tie-breaker.

## Matching Strategy Evaluation

The administrator evaluation endpoint simulates three approaches over current pending requests and available volunteers:

| Strategy | Behavior |
|---|---|
| `FIFO` | Processes requests in creation order. |
| `WEIGHTED_SCORING` | Processes requests using the weighted priority score. |
| `MULTI_OBJECTIVE_OPTIMIZATION` | Combines priority, urgency, geographic distance, and regional distribution. |

The simulation does not modify assignments. It returns comparative metrics so administrators can evaluate likely operational outcomes before changing production behavior.

## Technology Stack

| Layer | Technologies |
|---|---|
| Backend | Java 17, Spring Boot 3.2, Spring MVC |
| Security | Spring Security, JJWT 0.12.3 |
| Persistence | PostgreSQL, Spring Data JPA, Hibernate, JDBC |
| Frontend | HTML5, CSS3, vanilla JavaScript, Font Awesome |
| Email | Spring Mail with configurable SMTP |
| Mapping | MapStruct, Lombok |
| Testing | JUnit 5, Mockito, Spring Boot Test |
| Build | Maven Wrapper |

## Architecture

```mermaid
flowchart LR
    UI[Static HTML, CSS, and JS] --> API[REST Controllers]
    API --> SEC[JWT and Role Authorization]
    API --> SVC[Application Services]
    SVC --> MATCH[Priority, Geo, Crisis, and Evaluation Services]
    SVC --> REPO[JPA Repositories and JDBC]
    REPO --> DB[(PostgreSQL)]
    SVC --> MAIL[SMTP Email]
```

The application follows a layered structure:

```text
Controller -> Service -> Repository -> PostgreSQL
                     -> Matching and scoring services
                     -> Email and security services
```

## Project Structure

```text
Nidaa-project/
|-- database/
|   |-- migrations/                     # Versioned PostgreSQL changes
|   `-- backups/                        # Local database backups, ignored by Git
|-- postman/
|   |-- collections/Nidaa API/          # Version-controlled API requests
|   `-- globals/                        # Postman workspace variables
|-- src/
|   |-- main/
|   |   |-- java/com/humanitarian/platform/
|   |   |   |-- config/                 # Security and application configuration
|   |   |   |-- controller/             # REST endpoints
|   |   |   |-- dto/                    # Request and response contracts
|   |   |   |-- exception/              # API exception handling
|   |   |   |-- model/                  # JPA entities
|   |   |   |-- repository/             # JPA repositories
|   |   |   |-- security/               # JWT authentication
|   |   |   `-- service/                # Business and matching logic
|   |   `-- resources/
|   |       |-- static/                  # Frontend pages and assets
|   |       `-- application.properties  # Spring configuration
|   `-- test/java/                       # Unit, integration, and context tests
|-- mvnw / mvnw.cmd                      # Maven Wrapper
`-- pom.xml
```

## Prerequisites

- Java Development Kit 17.
- PostgreSQL. Version 17 is recommended for parity with the development environment.
- Git.
- An SMTP account if registration verification and password-reset email should work.
- `psql` available on `PATH`, or the full path to the PostgreSQL command-line tools.

Maven does not need to be installed globally because the repository includes the Maven Wrapper.

## Database Setup

> [!IMPORTANT]
> Hibernate schema generation is disabled with `spring.jpa.hibernate.ddl-auto=none`. The repository contains incremental V2-V4 migrations, but not a complete V1 bootstrap schema. A fresh empty database is therefore not enough. Load the project's existing base schema first, then apply every migration in version order.

Create the database if it does not already exist:

```sql
CREATE DATABASE "Web_DB";
```

After the base schema is present, apply migrations in version order:

```bash
psql -h 127.0.0.1 -U postgres -d Web_DB \
  -f database/migrations/V2__matching_and_assignment_history.sql
psql -h 127.0.0.1 -U postgres -d Web_DB \
  -f database/migrations/V3__location_resources_and_message_moderation.sql
psql -h 127.0.0.1 -U postgres -d Web_DB \
  -f database/migrations/V4__message_types_and_community_channel.sql
psql -h 127.0.0.1 -U postgres -d Web_DB \
  -f database/migrations/V5__provider_availability_preference.sql
```

Windows PowerShell example when PostgreSQL is not on `PATH`:

```powershell
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" `
  -h 127.0.0.1 -U postgres -d Web_DB `
  -f ".\database\migrations\V2__matching_and_assignment_history.sql"
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

V2 adds assignment history, V3 adds provider resources and moderation storage, V4 separates direct and community messages, and V5 adds provider availability preferences. See [database/migrations/README.md](database/migrations/README.md) for migration notes.

## Configuration

Use environment variables for credentials and secrets. Do not commit real database, JWT, or SMTP credentials.

| Variable | Purpose | Typical local value |
|---|---|---|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://127.0.0.1:5432/Web_DB?stringtype=unspecified` |
| `DB_USERNAME` | PostgreSQL user | `postgres` |
| `DB_PASSWORD` | PostgreSQL password | Set locally |
| `JWT_SECRET` | HMAC signing secret, at least 32 bytes | Generate a private random value |
| `JWT_EXPIRATION` | Access-token lifetime in milliseconds | `900000` |
| `JWT_REFRESH_EXPIRATION` | Refresh-token lifetime in milliseconds | `604800000` |
| `MAIL_HOST` | SMTP host | Provider-specific |
| `MAIL_PORT` | SMTP port | `465` for SMTP over SSL |
| `MAIL_USERNAME` | Sender account | Set locally |
| `MAIL_PASSWORD` | SMTP or application password | Set locally |
| `NIDAA_ADMIN_EMAIL` | Address receiving approval notifications | Set locally |
| `SERVER_PORT` | HTTP port | `8081` |

PowerShell example:

```powershell
$env:DB_URL = "jdbc:postgresql://127.0.0.1:5432/Web_DB?stringtype=unspecified"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "your-local-database-password"
$env:JWT_SECRET = "replace-with-a-private-random-secret-of-at-least-32-bytes"
$env:MAIL_HOST = "smtp.example.com"
$env:MAIL_PORT = "465"
$env:MAIL_USERNAME = "noreply@example.com"
$env:MAIL_PASSWORD = "your-smtp-application-password"
$env:NIDAA_ADMIN_EMAIL = "admin@example.com"
```

## Running Locally

Clone the repository:

```bash
git clone https://github.com/Khalil-Pal/Nidaa-project.git
cd Nidaa-project
```

Run on Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Run on Linux or macOS:

```bash
./mvnw spring-boot:run
```

Open:

- Application: [http://localhost:8081/](http://localhost:8081/)
- Health check: [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)

Build an executable JAR:

```powershell
.\mvnw.cmd clean package
java -jar target/platform-1.0.0.jar
```

## Authentication

Most API endpoints require an access token:

```http
Authorization: Bearer <access-token>
Content-Type: application/json
```

Login request:

```http
POST /api/auth/login
```

```json
{
  "email": "user@example.com",
  "password": "your-password"
}
```

Access tokens expire after 15 minutes by default. Use `/api/auth/refresh` with the refresh token to obtain a new token pair.

## Core API Endpoints

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/auth/register` | Public | Start email-verified registration. |
| `POST` | `/api/auth/register/verify` | Public | Verify the code and create the account. |
| `POST` | `/api/auth/login` | Public | Obtain access and refresh tokens. |
| `POST` | `/api/auth/refresh` | Public | Rotate the refresh token and issue new tokens. |
| `POST` | `/api/auth/forgot-password` | Public | Send a password-reset code. |
| `POST` | `/api/help-requests` | Authenticated | Submit a material help request. |
| `GET` | `/api/help-requests/my` | Authenticated | View the current beneficiary's requests. |
| `PUT` | `/api/help-requests/{id}/assign` | Volunteer, organization | Accept a pending request. |
| `GET` | `/api/v1/help-requests/ranked` | Admin, volunteer, organization | View ranked requests and nearest-volunteer suggestions. |
| `GET` | `/api/provider-availability/me` | Volunteer, organization | View effective and preferred availability. |
| `PUT` | `/api/provider-availability/me` | Volunteer, organization | Set personal matching availability. |
| `GET` | `/api/users/me/profile` | Authenticated | Load the server-backed profile and matching location. |
| `PUT` | `/api/users/me/profile` | Authenticated | Save profile details and matching location. |
| `POST` | `/api/psychological-requests` | Authenticated | Submit psychological support. |
| `GET` | `/api/psychological-requests/my` | Authenticated | View the current beneficiary's support requests. |
| `GET` | `/api/psychological-requests/pending` | Psychologist, admin | View unassigned psychological requests. |
| `PUT` | `/api/psychological-requests/{id}/accept` | Psychologist | Accept a pending support request. |
| `GET` | `/api/v1/assignments/my` | Authenticated | View personal assignment history. |
| `GET` | `/api/v1/admin/dashboard/ranked` | Admin | View material and psychological priority queues. |
| `GET` | `/api/v1/admin/assignments` | Admin | View complete assignment history. |
| `GET` | `/api/v1/admin/evaluation` | Admin | Compare matching strategies and metrics. |
| `GET` | `/api/community/messages` | Volunteer, psychologist, organization, admin | View paginated shared community messages. |
| `POST` | `/api/community/messages` | Volunteer, psychologist, organization, admin | Publish a community message. |
| `DELETE` | `/api/community/messages/{id}?reason=...` | Admin | Soft-delete a message with mandatory justification. |
| `GET` | `/api/admin/community/deletions` | Admin | Review the community moderation audit. |

## Example Help Request

Coordinates are required for automatic geographic assignment:

```json
{
  "title": "Food needed for a family",
  "description": "Food assistance needed for four people",
  "helpType": "FOOD",
  "urgencyLevel": "HIGH",
  "peopleCount": 4,
  "hasChildren": true,
  "hasElderly": false,
  "hasDisabled": false,
  "address": "Amman, Jordan",
  "latitude": 31.9539,
  "longitude": 35.9106
}
```

## Postman Requests

Version-controlled Postman request definitions are available in [postman/collections/Nidaa API](postman/collections/Nidaa%20API/). They cover authentication, help requests, psychological requests, ranked queues, assignment history, administrator dashboards, and algorithm evaluation.

Set the workspace base URL to:

```text
http://localhost:8081/api
```

After login, use the returned access token as the Bearer token for protected requests.

## Testing

Run the complete test suite:

```powershell
.\mvnw.cmd test
```

Linux or macOS:

```bash
./mvnw test
```

The current tests cover:

- Application-context startup.
- Request lifecycle behavior.
- Weighted priority scoring.
- Geographic distance and combined volunteer/organization matching.
- Resource-aware automatic, ranked, and manual provider matching.
- Concurrent-safe automatic assignment behavior.
- Crisis detection and crisis routing.
- Assignment history mapping.
- Matching strategy evaluation metrics.
- Ranked administrator dashboard contracts.
- Community role enforcement and direct-message isolation.
- Mandatory moderation reasons, soft deletion, and audit snapshots.

## Current Constraints

The following boundaries are important when evaluating the current implementation:

- The repository does not yet include a complete V1 database bootstrap migration.
- Automatic provider assignment filters by structured help-type resources, and ranked admin responses report numeric sufficiency against `peopleCount`; capacity is not decremented or reserved, and free-form skills are not matched.
- Providers with coordinates only in legacy volunteer columns must save them to `profiles` before automatic matching can consider them.
- The help-request form currently submits a textual address without browser-captured coordinates, so UI-created requests require a future geocoding/location step for automatic matching.
- Volunteer profile skills, schedule, and textual location are currently stored by the frontend and are not fully synchronized with the backend volunteer record.
- Haversine distance is straight-line distance, not a road route or travel-time estimate.
- Waiting-time priority points are not capped, so very old requests can produce unusually large scores.
- Strategy comparison is an analytical simulation and does not yet apply provider-resource eligibility; production material assignment uses resource-filtered nearest-volunteer matching.
- Community messages and moderation are server-backed, but likes/comments remain browser-local and community photo posts are not yet supported.

## Production Checklist

Before deploying Nidaa outside a development environment:

1. Rotate every database, SMTP, and JWT secret and provide them through environment variables or a secret manager.
2. Remove secret-bearing fallback values from tracked configuration.
3. Restrict public role registration so administrator accounts cannot be self-provisioned.
4. Restrict CORS to trusted frontend origins.
5. Add a complete V1 migration and an automated migration tool such as Flyway or Liquibase.
6. Backfill any legacy provider coordinates into `profiles` and add transactional resource-capacity consumption.
7. Add geocoding or browser location capture with explicit user consent.
8. Configure HTTPS, secure headers, centralized logs, monitoring, and database backups.
9. Add continuous integration for tests and build verification.

## Contributing

1. Create a focused feature branch.
2. Keep changes within the relevant controller, service, repository, and frontend boundaries.
3. Add or update tests for behavioral changes.
4. Run `./mvnw test` or `.\mvnw.cmd test` before opening a pull request.
5. Include migration instructions whenever database structure changes.

```bash
git checkout -b feature/descriptive-name
git add <changed-files>
git commit -m "feat: describe the change"
git push -u origin feature/descriptive-name
```

## Project Status

Nidaa is under active development. The core request, psychological support, resource-aware combined provider assignment, ranking, and evaluation workflows are implemented. The next major steps are transactional inventory allocation and a reproducible fresh-database bootstrap.
