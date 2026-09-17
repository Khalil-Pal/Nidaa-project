# Nidaa — Security

This document describes what Nidaa protects, who it protects it from, which controls
exist and where they live in the code, what an independent audit found and how each
finding was fixed, and which limitations remain by choice. It is written so that a
reader who has not seen the code can judge the security posture, and so that a
developer can find the enforcement point for any rule in under a minute.

The remediation happened between September 2026 and the feature freeze. Every fix
below is one commit on the `audit-remediation` branch, tagged with the audit
identifier in its message (`Audit-Ref: S-1` and so on), so `git log --grep` finds it.

---

## 1. What is being protected

Nidaa serves people in acute need: beneficiaries asking for food, shelter, water,
medical help or clothing, and people asking for psychological support, some of whom
are in crisis or fleeing violence. The assets, in order of sensitivity:

1. **Psychological requests** — free-text descriptions of mental-health state,
   crisis flags, and the identity of the person behind an `isAnonymous` request.
2. **Account existence and state** — on a platform used by people fleeing domestic
   violence, learning that a particular email has an account is itself a disclosure.
3. **Contact details** — phone numbers and addresses of beneficiaries and providers,
   revealed only to the other party of an assignment.
4. **Aid records** — assignments and completions, which are the platform's evidence
   that help was delivered and must not be falsifiable or erasable.
5. **Administrative control** — approving providers, moderating the community feed,
   reading every request.
6. **Availability** — the matching pipeline and login must keep working under load
   or abuse.

## 2. Threat model

**Actors considered**

| Actor | Capability | Motivation |
|---|---|---|
| Anonymous internet user | Any HTTP request, unlimited registrations | Gain admin, enumerate accounts, mail-bomb, deny service |
| Registered beneficiary | Valid token, can file requests and messages | Read other people's requests, inject scripts seen by admins, discover who else has an account |
| Registered provider (volunteer / organization) | Valid token, can accept and complete requests | Falsify delivery records, complete or cancel requests not assigned to them, self-assign manufactured requests |
| Compromised account | Stolen access or refresh token | Keep access after the owner changes their password |
| Malicious administrator | Full application access | Out of scope: an admin is trusted by definition; their actions are logged (Phase 4) |

**Trust boundaries**

- Browser ↔ API: every request carries a bearer JWT; nothing is trusted from the
  client except what the token proves.
- API ↔ database: the application is the only writer; the database enforces the
  invariants that must hold regardless of application bugs (enum labels, CHECK
  constraints on assignments, the filer ≠ beneficiary rule, the priority range).
- Application ↔ external services: SMTP for email only. No third-party data feeds.

**Out of scope** (documented, not forgotten): physical security of the server,
compromise of the PostgreSQL host, SMTP transport security beyond TLS, and a
malicious administrator.

## 3. Controls

### 3.1 Authentication

| Control | Where |
|---|---|
| Passwords hashed with BCrypt (strength 10) | `SecurityConfig.passwordEncoder()` |
| Access token: HMAC-signed JWT, 15 minutes, subject = email, no role claim (role is read from the database on every request so a role change takes effect immediately) | `JwtUtils`, `JwtAuthenticationFilter` |
| Refresh token: opaque random string, 7 days, stored server-side, **rotated on every use** | `AuthService.refresh()`, `RefreshTokenRepository` |
| Silent renewal in the browser: on 401 the shared module refreshes once and retries; concurrent failures share one refresh | `static/js/nidaa-common.js` `apiFetch()` (F-4) |
| Password change or reset ends every session: refresh tokens deleted, and access tokens issued before `users.tokens_valid_from` are refused | `PasswordResetService`, `PasswordChangeService` (set `tokensValidFrom` and save), `JwtAuthenticationFilter.isRevoked()` (S-7, migration V9) |
| Deactivated or locked accounts lose access immediately, not at token expiry | `JwtAuthenticationFilter.isRevoked()` (S-7) |
| Logout revokes the refresh token server-side | `AuthService.logout()`, `nidaa-common.js logout()` |
| Login lockout: 5 failures per (email, client IP) for 15 minutes, expired entries evicted on every read | `AuthService.login()` (S-9) |
| Account state (pending approval, deactivated, locked) is disclosed only **after** the password has been verified | `AuthService.login()`; the provider's pre-authentication checks are disabled for this reason and the two enforcement points are named at the disable site (S-8, PA-1) |

### 3.2 Authorization

Authorization is layered so that forgetting one layer fails closed rather than open.

| Layer | Rule | Where |
|---|---|---|
| Notifications are the caller's own: every read and write is scoped to the authenticated user inside the service, and another person's id is **404**, never 403, so ids cannot be probed | `NotificationService`, `NotificationRepository.findByIdAndUserId()` (N-1) |
| URL patterns (deny by default) | Listing help requests: ADMIN, VOLUNTEER, ORGANIZATION. Creating them: BENEFICIARY, VOLUNTEER, ORGANIZATION. Everything under `/api/psychological-requests`: BENEFICIARY, PSYCHOLOGIST, ADMIN. Provider resources and availability: VOLUNTEER, ORGANIZATION. `/api/psychologists/**` (duty toggle): PSYCHOLOGIST. `/api/admin/**` and `/api/v1/admin/**`: ADMIN. Static files, `/api/auth/**`, the public statistics and the API explorer (`/swagger-ui/**`, `/v3/api-docs/**`): anonymous. Anything else: any authenticated user. | `SecurityConfig.filterChain()` (P-1, UX-2, DEP-2) |
| Method annotations | `@PreAuthorize` on controllers for role checks the URL rules do not express | controllers |
| Row-level ownership | A request is returned only to an admin, its beneficiary, whoever filed it for them, or the assigned provider, **resolved by profile id**, never by comparing a user id to a profile id. Everyone else receives **404, not 403**, so the response does not confirm the record exists. | `HelpRequestService.getRequestById()`, `PsychologicalRequestService.getRequestById()` (S-4) |
| Status transitions per role | Beneficiary and filer: `CANCELLED` only. Assigned provider: `IN_PROGRESS` ("on my way", W-1), `COMPLETED`, `CANCELLED`; assigned psychologist: `COMPLETED`, `CANCELLED`. Admin: any valid transition. Authorization is checked **before** transition validity so an unrelated caller learns nothing about the current state. | `HelpRequestService.updateStatus()`, `PsychologicalRequestService.updateStatus()` (S-5) |
| Contact reveal | Beneficiary and filer see the assigned provider; the provider sees the beneficiary; an anonymous psychological request never reveals identity to anyone. | `ContactInfoService` |
| Consultation records | One row per session (V22); the assigned psychologist records on an ASSIGNED or COMPLETED case, the beneficiary rates each session once. `notes_for_psychologist` is returned only to the assigned psychologist; every other response, the administrator's included, is built without it and the key is absent from the JSON. No consultation response or notification carries the beneficiary's id or name, so an anonymous case stays anonymous when the beneficiary rates it. Non-parties get **404**, for the list and for a session id that is not the case's. | `ConsultationService` (CS-1) |
| Conflict of interest | A provider cannot accept a request they filed on someone's behalf, manually or through automatic matching. | `HelpRequestService.assignToMe()`, `AutomaticAssignmentService` (ON-1) |
| Community engagement | Likes and comments use the feed's role gate (`CommunityRules`); a like is one row per (post, user) by primary key; removing a comment is admin-only, needs a reason, keeps the row soft-deleted, writes the same `message_deletions` audit row as a removed post (`comment_id` set) and tells the author the reason, not the moderator's name. | `CommunityEngagementService` (CM-1) |
| Self-registration whitelist | `ADMIN` cannot be self-registered; the first administrator is inserted directly in the database. | `AuthService.register()` (S-1), `database/migrations/README.md` |

Status-code contract: **401** means no valid token (missing, malformed, expired,
revoked). **403** means a valid token without the role. **404** means the record
does not exist *or* the caller may not see it. **400** means the request was
rejected by validation or a business rule. **409** means the record changed under
the caller: another actor won a status race or already applied the same
transition (B-4). Every body, success or error, is the one envelope
`{"success", "message", "data"?, "details"?}` (P-2), whichever layer wrote it.

### 3.3 Input handling

| Control | Where |
|---|---|
| Bean Validation on every DTO: lengths, `@Pattern` whitelists for help type, urgency, category, support type and format. Unknown values are refused with a field-level 400, never coerced to a default. | `dto/*` (B-2) |
| Help-request titles may not contain `<` or `>`; descriptions and addresses are length-capped | `HelpRequestDto` (S-2) |
| Every server- or user-supplied string rendered into `innerHTML` passes through `escHtml()`; rendered controls carry `data-*` attributes read back with `Number()` rather than values interpolated into handler code (there are no inline handlers since F-5) | all pages, `nidaa-common.js` (S-2, F-5) |
| Content-Security-Policy on every response: `default-src 'self'`, `script-src 'self' https://cdn.jsdelivr.net` (no inline script or handler runs, F-5), `connect-src 'self'` (an injected script cannot send data to another origin), `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'` | `SecurityConfig.CONTENT_SECURITY_POLICY` (S-2, F-5) |
| `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `Strict-Transport-Security: max-age=31536000; includeSubDomains` on every response, including 401/403 written by the filters. HSTS is sent on plain HTTP too because a TLS-terminating proxy hands the application plain HTTP; browsers act on it only over HTTPS. Preload is not requested. | `SecurityConfig` headers block (DEP-5) |
| Normalizers throw on unknown input instead of returning `OTHER` / `MEDIUM` / `ANXIETY` | `HelpTypeNormalizer`, request services (B-2) |

### 3.4 Information disclosure

| Control | Where |
|---|---|
| Unknown email and wrong password produce the identical 401 body; no remaining-attempts counter | `AuthService.login()` (S-8) |
| Forgot-password always answers 200 with the same sentence, whether or not the account exists, whether or not the email could be sent | `PasswordResetController`, `PasswordResetService` (S-8) |
| Unexpected errors return only an 8-character reference; the stack trace, constraint and column names go to the log under that reference. HTTP status is chosen by exception type, never by message text. | `GlobalExceptionHandler` (S-10) |
| Records the caller may not see return 404 (see 3.2) | request services (S-4) |
| A request filed on someone's behalf carries the beneficiary's name (`beneficiaryName`, transient) only in responses to the beneficiary, the filer, the assigned provider or an admin — the people who can already learn it; a provider browsing the open list sees that it was filed, not for whom | `HelpRequestService.withBeneficiaryNames()` (ON-2) |
| CORS reflects only configured origins (`CORS_ORIGINS`) and accepts only `Authorization` and `Content-Type` | `SecurityConfig.corsConfigurationSource()` (S-6) |

### 3.5 Abuse and availability

| Control | Where |
|---|---|
| Rate limiting: 5 requests/minute per IP on `/api/auth/**`; 100/minute per authenticated user elsewhere; 429 with `Retry-After`; idle buckets swept | `RateLimitFilter` (S-9), configurable via `app.ratelimit.*` |
| Password-reset and password-change codes are discarded after 5 wrong attempts | `PasswordResetService`, `PasswordChangeService` (S-9, migration V10) |
| Priority recalculation is paged and per-row, so one bad row cannot stop scoring for the whole system | `PriorityScoreScheduler` (D-5) |
| Capacity reservation under concurrency uses pessimistic row locks; request claiming **and every status transition** use a conditional `UPDATE ... WHERE status = :expected` whose row count decides the race; the loser gets 409, never a silent overwrite | `ProviderResourceService`, `HelpRequestRepository`, `PsychologicalRequestRepository` (B-4) |
| Listing endpoints are paged (default 20) so a large queue cannot be pulled in one response; statistics are `COUNT`/`GROUP BY` queries, never table loads | request services, `AdminReportService` (Q-2, Q-3) |

### 3.6 Data protection

| Control | Where |
|---|---|
| Account deletion anonymises in place: email, name and phone are replaced, the profile address and coordinates cleared, sessions ended; requests, assignments and messages keep their foreign keys with the person unidentifiable. Self-deletion requires the current password. | `UserService.deleteAccount()`, `UserRepository.softDelete()` (D-2, migration V11) |
| Soft-deleted accounts cannot log in, be resolved by email, or appear in approval queues | `UserRepository.findByEmail()` (D-2) |
| Crisis detection is scored on word boundaries; generic words no longer flag ordinary requests as crises | `CrisisDetectorService` (L-2) |
| Secrets (`DB_PASSWORD`, `JWT_SECRET`, `MAIL_PASSWORD`) have no defaults; startup fails without them; the tracked configuration file contains placeholders only | `application.properties.example` (S-3a) |

### 3.7 Accountability

| Control | Where |
|---|---|
| Every request carries a correlation id (`X-Request-Id`, generated unless the caller supplies a safe token) that appears in every log line written while it is handled, together with the authenticated user | `RequestCorrelationFilter`, `JwtAuthenticationFilter`, `logback-spring.xml` (C-4, DEP-6) |
| Administrator actions are written to `activity_logs` in the same transaction as the action: approval, rejection, deletion, (de)activation of an account, verification (or revocation) of a psychologist's credentials, and any status override on a request; with actor, entity, details, IP and user agent | `AdminAuditService` (C-4) |
| Authorization failures are logged at WARN with principal, method and path from both enforcement points (URL rules and ownership checks); assignment decisions and crisis detections are logged, the latter without the person's text | `SecurityConfig`, `GlobalExceptionHandler`, request and assignment services (C-4) |
| The log file rolls daily or at 10 MB, 14 days kept, 500 MB cap; the `prod` profile logs at INFO and never prints SQL | `logback-spring.xml`, `application-prod.properties` (DEP-6) |

## 4. Audit findings and their resolution

An independent full-stack audit (architecture, backend, database, security,
frontend, testing) preceded the remediation. Findings, in the order they were fixed:

| ID | Severity | Finding | Resolution | Commit |
|---|---|---|---|---|
| S-1 | Critical | `POST /api/auth/register` accepted `"role":"admin"` and, because ADMIN needed no approval, issued a valid admin token | Self-registration whitelist; first admin inserted by SQL | `1210063` |
| D-1 | Critical | Two of six psychological categories mapped to enum labels that did not exist and failed with 500; a third was silently stored as ANXIETY | Mapping corrected to the real labels; form sends enum values | `9c67c27` |
| UX-1 | Medium | Landing-page statistics returned 401 to visitors | Endpoint permitted anonymously | `d0d43fd` |
| S-6 | High | CORS reflected any origin with credentials | Configured origin list, two allowed headers | `4070f94` |
| S-3a | High | Working secrets were hardcoded as configuration defaults | Defaults removed; startup fails fast; example file added | `1e5f56a` |
| T-1 | — | No HTTP-layer, authentication or authorization tests existed | 26 `@WebMvcTest` tests written first, 13 failing by design | `68c3730` |
| S-15 | High | Unauthenticated requests returned 403, so token refresh could never trigger; the fix initially turned wrong-role 403s into 401s via the error-page dispatch | JSON 401 entry point and direct 403 handler | `d18acb5`, `295bbdf` |
| ON-1 | — | Providers could not file for someone else without ambiguity in ownership | `filed_by_user_id` column, on-behalf creation, conflict-of-interest guard | `ae79532` |
| S-4 | Critical | Any authenticated user could read any request by id, including mental-health disclosures | Ownership check returning 404 | `3639f0d` |
| S-5 | Critical | Any volunteer could complete or cancel any request; a beneficiary could complete their own | Assignee comparison by profile id; per-role transition table | `5f3b714` |
| P-1 | High | A forgotten `@PreAuthorize` silently opened an endpoint to every user | Deny-by-default URL rules | `59d32f9` |
| S-10 | High | HTTP status chosen by message text; database constraint names returned to the browser | Typed handlers; opaque reference for 500 and 409 | `2b3ce10` |
| S-8 | High | Unknown email (404) and wrong password (400 with remaining attempts) were distinguishable; forgot-password confirmed account existence | Identical 401; uniform forgot-password reply; state disclosed only after password verification | `46dca81` |
| S-7 | High | Password change left existing access and refresh tokens valid for up to 7 days | `tokens_valid_from` check in the filter; refresh tokens deleted | `4f2f36c` |
| S-9 | High | No rate limiting; unlimited attempts against reset codes; lockout map never pruned | bucket4j filter; attempt counter; bounded (email, IP) lockout | `8032e4a` |
| D-2 | High | Deleting a user failed on foreign keys or cascaded away their aid history | Anonymise in place; password re-entry | `0fdc611` |
| L-1 | Critical | The form never sent coordinates, so automatic matching never ran for real requests | Geolocation with consent copy; saved-location prefill; manual-matching fallback message | `0dff000` |
| PA-1 | — | Disabled pre-authentication checks would be inherited silently by any new login path | Comment naming both enforcement points | `ae0f631` |
| F-2 | High | Every page duplicated `API`, auth helpers, escaping and navigation, blocking the XSS and refresh fixes | Shared `nidaa-common.js` | `72b8995` |
| S-2 | Critical | Beneficiary-supplied text rendered unescaped into the admin queue; a title could exfiltrate the admin's token | Escape on render, validate on write, Content-Security-Policy | `fb06b0d` |
| F-4 | High | Refresh tokens were never used; every user was logged out after 15 minutes | Stored pair; refresh-and-retry in `apiFetch()`; server-side logout | `731a76e` |
| B-2 | Medium | Unknown help type became the unassignable OTHER; unknown urgency became MEDIUM | Reject with field-level 400 | `7aca427` |
| L-2 | High | Crisis detection matched "urgent" and "help me" as substrings, flooding the crisis queue | Weighted two-tier scoring on word boundaries; review flag | `d397bfe` |
| D-5 | Medium | Aging bonus uncapped; a single row over 100 would stop recalculation for everyone; a V1 trigger silently overrode the documented model | Cap and clamp; per-row paged recalculation; trigger removed | `d85c493` |
| T-2 | — | No test touched a real database, which is why D-1, the phone NOT NULL failure and the cascade failures shipped | 44 `@DataJpaTest` tests against a migration-built database | `cd15e71` |
| D-6 | Medium | `users.role` was `varchar` while its default cast to the `user_role` enum, forcing native-SQL workarounds around every role write | Column converted to the enum (V17); workarounds deleted | `cf664d3` |
| A-2 | Medium | Approval spanned a JPA update and three `JdbcTemplate` inserts inside a controller, so a failure part-way left an account active with no profile | One transactional `UserApprovalService`; reports in `AdminReportService`; no SQL in controllers | `d709593` |
| P-2 | Low | Success bodies had three shapes and error bodies a fourth, so clients read `data.data \|\| data` | One envelope from controllers, exception handler and filters | `74f1156` |
| B-4 | High | Status `UPDATE`s matched on id alone, so two callers reading the same state both wrote and the second silently won | `AND status = :expected`; 0 rows is 409 | `10f5f66` |
| C-4 | Medium | Nothing logged authorization failures, admin actions, assignment decisions or crisis detections, and log lines could not be tied to a request | Correlation id, `activity_logs` audit, decision logging | `71b6bc9` |
| UX-2 | Medium | `is_on_duty` gated crisis routing and could only be changed with SQL | Duty toggle endpoint and UI; the UI says when verification still blocks routing | `d40cd2a` |
| F-5 | High | 177 inline handlers and inline page scripts kept `script-src 'unsafe-inline'`; 57 WCAG A/AA violations (unnamed controls, unlabelled inputs) | Scripts in `/js`, handlers wired by id, `script-src 'self'`; 0 axe violations | `cf06f88` |
| DEP-5 | Medium | `Strict-Transport-Security` was never sent behind a TLS proxy | Four headers on every response, HSTS unconditional | `5515453` |
| DEP-6 | Low | SQL statements printed in every environment; a 237 KB log file was committed | `prod` profile without SQL, rolling file, log removed from the repository | `ad511f5` |

## 5. How the controls are verified

- **HTTP-layer tests** (`src/test/java/.../security`) load the real `SecurityConfig`,
  the real JWT utilities and the real service under test with repositories mocked,
  so URL rules, method security, ownership and transitions are all exercised through
  `MockMvc`. They include an expired token, a token issued before a password change,
  a token for a deactivated account, both account-enumeration vectors, the lockout,
  the XSS payload as a title, the four security headers, the strict `script-src`,
  the lost-race 409 and the audit call on an admin override.
- **`StaticPagesCspTest`** fails the build if any page or page script regains an
  inline `<script>`, an `on*` attribute or a `javascript:` URL.
- **Persistence tests** (`src/test/java/.../persistence`) run against a PostgreSQL
  database that Flyway builds from the migrations; they cover every enum label, the
  CHECK and FK constraints the controls depend on, soft deletion with dependent
  rows, the guarded status updates, approval with its `activity_logs` row, and the
  duty flag's effect on the crisis-routing pool. CI runs them against a real
  PostgreSQL service and fails if they skip (DEP-4).
- **Browser checks** (`scripts/gate/`): the XSS payload rendered as text, silent
  token refresh, zero CSP violations and zero axe-core WCAG A/AA violations on all
  18 pages, keyboard-only operation of the beneficiary-facing pages.
- **Live verification**: every finding above was also reproduced and re-checked with
  `curl` or in Chrome against the running application and the real database before
  its commit; B-4 with twelve concurrent completions of one request (one 200,
  eleven 409).
- The suite grew from 89 to 296 tests during the remediation; all pass.

## 6. Known limitations and residual risks

Chosen deliberately and documented, rather than gaps:

1. **`style-src` still allows inline styles.** Since F-5 `script-src` is
   `'self' https://cdn.jsdelivr.net` (Chart.js on the analytics page): every page's
   script is a file under `/js`, there are no `on*` attributes and no
   `javascript:` URLs, so an injected script or handler attribute does not run.
   Inline `style="..."` attributes remain (about 200) and `'unsafe-inline'` stays
   in `style-src`; CSS injection cannot run code or read the token, so this is
   accepted.
2. **No negation handling in crisis detection.** "I am not suicidal" scores as a
   crisis, which is the safer error. HIGH-tier terms are stems, so inflected forms
   ("suicidality", "self-harming", "суициде", "الانتحار") do match; MED-tier terms
   are exact words. The review band exists so borderline text reaches a human
   without automatic routing.
3. **Refresh-token rotation is per account.** Two browser tabs that both refresh
   will log the slower one out. Per-device tokens would fix this at the cost of a
   token table per session.
4. **Rate limiting is per instance and in memory.** Correct for a single-node
   deployment; a second node would need a shared store, which the project
   deliberately does not add.
5. **Client IP is the socket address.** Behind a reverse proxy, set
   `server.forward-headers-strategy=native`; the `X-Forwarded-For` header is not
   read directly because a client can forge it. The same setting lets Spring see
   the request as HTTPS, which matters for the secure-cookie and scheme checks;
   HSTS itself is already sent unconditionally.
6. **On-behalf-of filing requires the beneficiary's email**, because the only way
   for that person to claim the account later is the email-based password reset.
7. **Tokens live in `localStorage`.** An `httpOnly` cookie would be stronger against
   script access but requires CSRF protection and a different session model; with
   the CSP and escaping in place this was judged acceptable for the project's scale.
8. **Story submissions live in the browser's `localStorage`**, not on the server, so
   they can only affect the browser that wrote them.

## 7. Operating the controls

- Secrets: `DB_PASSWORD`, `JWT_SECRET`, `MAIL_PASSWORD` via environment or a
  gitignored `.env`; see README "Configuration". Rotating `JWT_SECRET` invalidates
  every access and refresh token at once, which is the intended emergency lever.
- Origins: `CORS_ORIGINS`. Rate limits: `RATELIMIT_AUTH_PER_MINUTE`,
  `RATELIMIT_API_PER_MINUTE`, `RATELIMIT_ENABLED`.
- Creating the first administrator, and every schema change, is documented in
  `database/migrations/README.md`; Flyway applies the migrations at start-up.
- Run with `SPRING_PROFILES_ACTIVE=prod` (docker compose does) for INFO logging
  without SQL and error pages without exception text. The API explorer at
  `/swagger-ui.html` is public; set `springdoc.api-docs.enabled=false` to hide it.
- Administrator actions are in `activity_logs`; each row's `details` names what
  changed, and the log line for the same action carries the request id, so the two
  can be joined by time and actor when investigating.
- Two SMTP application passwords and one database password that appeared in this
  repository's history before the remediation must be treated as compromised and
  rotated by the account owner; the code no longer contains any of them.
