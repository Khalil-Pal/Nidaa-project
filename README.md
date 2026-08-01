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
- Suggest or automatically assign the nearest available volunteer when coordinates exist.
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

1. Loads volunteers whose `isAvailable` flag is true.
2. Removes volunteers without coordinates.
3. Calculates straight-line distance with the Haversine formula.
4. Orders candidates from nearest to farthest.
5. Atomically claims the first available volunteer.
6. Changes the request from `PENDING` to `ASSIGNED`.
7. Saves an `AUTO_GEO` assignment-history record with the distance.

The atomic claim prevents two simultaneous requests from assigning the same volunteer. When an assignment is completed or cancelled, the volunteer is released if no other active assignment remains.

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

## Project Status

Nidaa is under active development. The core request, psychological support, assignment, ranking, and evaluation workflows are implemented. The next major step is completing resource-aware volunteer matching and a reproducible fresh-database bootstrap.
