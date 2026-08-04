# Nidaa Frontend Guide

The Nidaa frontend is a set of static HTML pages with inline page-specific CSS and
vanilla JavaScript. Spring Boot serves the files from
`src/main/resources/static`. There is no frontend build step or client framework.

## Runtime Model

Open the application through Spring Boot rather than directly from the filesystem:

```text
http://localhost:8081/login.html
```

Using the backend origin allows relative `/api` requests, static assets, and CORS
behavior to match the deployed application.

The main browser-side request pattern is:

```javascript
fetch('/api/example', {
  headers: {
    Authorization: `Bearer ${localStorage.getItem('token')}`,
    'Content-Type': 'application/json'
  }
});
```

## Authentication State

After login, the browser stores the access token and a safe user summary in
`localStorage`. Protected pages redirect to `login.html` when no token exists.
Role-specific pages inspect the stored user role for navigation and visibility, but
the backend remains authoritative and repeats all role/ownership checks.

Logout removes the token and user summary before returning to the login page.

## Layout And Navigation

`css/shared-layout.css` contains the canonical top bar and sidebar styles. Pages may
retain content-specific CSS, but should not redefine shared dimensions, typography,
or nav spacing.

Navigation is role-based:

- Beneficiaries: dashboard, help requests, psychological support, stories, profile,
  and settings.
- Volunteers: dashboard, help requests, community, profile, and settings.
- Psychologists: dashboard, community, psychological support, profile, and settings.
- Organizations: dashboard, help requests, community, profile, and settings.
- Administrators: request management, analytics, approvals, users, stories,
  community moderation, and settings.

## Main Pages

| Page | Main responsibility |
|---|---|
| `login.html` | Authentication and token creation |
| `dashboard.html` | Role-specific summary and navigation |
| `help-requests.html` | Material request submission, queue, assignment, and contacts |
| `psychological.html` | Psychological request submission, queue, assignment, and contacts |
| `settings.html` | Server-backed account details and provider services |
| `profile.html` | Profile presentation and provider information |
| `community.html` | Shared responder/admin feed and admin moderation |
| `admin.html` | Administrator analytics |
| `admin-requests.html` | Cross-domain request management |
| `admin-approvals.html` | Provider approval workflow |
| `admin-users.html` | User administration |
| `admin-stories.html` | Story moderation |

## Community Feed

`community.html` is a real multi-user feature. It no longer stores the shared post
feed in `nidaa_community_posts`.

### Access

Volunteers, psychologists, organizations, and administrators can open the page,
list messages, and post. Beneficiaries are redirected by the page and rejected by
the API with `403` if they call it directly.

### Loading And Pagination

The page requests:

```http
GET /api/community/messages?page=0&size=20
```

It renders `data.content`, tracks the Spring page's `last` flag, and exposes a
stable Load More button when another page exists. Loading, empty, retry, and API
error states are handled without replacing the rest of the page.

### Creating A Message

The composer sends:

```http
POST /api/community/messages
Content-Type: application/json
```

```json
{
  "content": "Shared community update",
  "communityCategory": "UPDATE"
}
```

The post is added to the visible feed only after the server returns a successful
created message. Content is limited to 1000 characters in both UI and backend.

### Categories

The existing category tabs and composer values use the backend's normalized forms:

- `UPDATE`
- `SUCCESS_STORIES`
- `QUESTION`
- `TIPS_ADVICE`
- `EVENTS`
- `RESOURCES`
- `GRATITUDE`

Search, role, date, and category filtering run over the pages currently loaded in
the browser.

### Admin Moderation

Administrators see a Moderate button on every shared message. Clicking it opens an
inline form containing a required deletion-justification field.

The browser blocks an empty submission and places the error beside the field. A
valid submission sends:

```http
DELETE /api/community/messages/{messageId}?reason={encodedReason}
```

The backend repeats validation and authorization. On success, the message is
removed from the visible feed and the Moderation History panel refreshes from:

```http
GET /api/admin/community/deletions?page=0&size=5
```

The panel shows the original author, deletion time, justification, content snapshot,
and responsible administrator.

## Server And Browser State Boundaries

| Feature | Storage | Multi-user? |
|---|---|---|
| Community message content/author/category/time | PostgreSQL through `/api/community/messages` | Yes |
| Message soft deletion and audit | PostgreSQL | Yes, admin-only audit |
| Community likes | `nidaa_community_engagement` in `localStorage` | No |
| Community comments | `nidaa_community_engagement` in `localStorage` | No |
| Community photo posts | Not currently supported | No |
| JWT and safe user summary | `localStorage` | Session convenience only |
| Cached profile display preferences/avatar | `localStorage` on relevant pages | Browser-local |

Likes and comments are deliberately described as local interactions. Adding true
shared reactions requires dedicated database tables, endpoints, ownership rules,
and moderation behavior; it should not be simulated by attaching them to message
content.

## Contact Reveal UI

Material and psychological pages request contact information only after assignment.
The backend decides whether the current user is the requester or assigned provider.
Anonymous psychological requests display protected identity messaging instead of
beneficiary phone/email.

Frontend role checks are presentation only. Never reveal cached contact data when
an API call fails or returns an anonymous payload.

## Provider Services UI

Volunteers and organizations manage structured services in `settings.html` through
`/api/provider-resources`. Volunteers can also update occupation. Provider values
are loaded from the backend and should not be replaced with local profile-only
fields.

The same settings area exposes a server-backed availability toggle for volunteers
and organizations. It reads and writes `/api/provider-availability/me`; it does not
change or complete an active assignment.

## Ranked Request Capacity

`admin-requests.html` displays the capacity assessment returned by the ranked
material-request API beside the suggested provider and in the request detail modal:

- `capacitySufficient: true` displays **Capacity sufficient**.
- `capacitySufficient: false` displays **Capacity insufficient**.
- `capacitySufficient: null` displays **Capacity unknown**, including qualitative
  resources that cannot be compared numerically.

For numeric resources, the detail modal also shows the provider amount and requested
people count. The badge is informational: it does not hide the suggestion or imply
that automatic assignment skipped a nearer provider.

## Matching Location UI

`settings.html` shows Matching Location for beneficiaries, volunteers, and
organizations. Saved values load from `GET /api/users/me/profile` and are persisted
with `PUT /api/users/me/profile`. Psychologists do not see this section because
their crisis routing is duty-based rather than distance-based.

Latitude and longitude are visible, editable fields. Browser geolocation only fills
the fields; the user must explicitly choose Save Location before anything is sent
to the server. The page reports whether coordinates are currently saved.

## Error Handling

For API calls:

1. Check `response.ok` before rendering success state.
2. Read the backend `message` field when available.
3. Keep validation errors near the relevant control when the user can correct them.
4. Use a short toast for request-level success or failure.
5. Do not add optimistic state permanently until the server confirms the write.
6. Redirect to login when authentication has expired and the endpoint rejects the
   token.

Escape user-generated content before inserting it into HTML templates. The
community feed uses `escHtml` for names, message text, comments, audit reasons, and
content snapshots.

## Local Verification

Start Spring Boot:

```powershell
.\mvnw.cmd spring-boot:run
```

Then verify each relevant role in the browser:

1. Log in as a volunteer, psychologist, organization, or admin.
2. Open `community.html` and confirm messages load.
3. Create a categorized post and reload the page to confirm persistence.
4. Confirm search/category/date/role filters do not alter server data.
5. As admin, open Moderate and submit a blank reason; verify the inline error.
6. Submit a real reason and verify the message disappears and history updates.
7. Log in as a beneficiary and verify the page/API are inaccessible.
8. Check desktop and narrow mobile widths for overflow and overlapping controls.

For matching settings, also verify that beneficiary, volunteer, and organization
accounts can load, fill, and save both coordinates; psychologist accounts should
not see the section. Confirm the availability toggle appears only for volunteers
and organizations and survives a reload.

As an administrator, open `admin-requests.html` with ranked material requests that
exercise sufficient, insufficient, and qualitative provider capacities. Verify the
table badge and modal detail show the same state, and that psychological requests do
not display a capacity badge.

Run a JavaScript syntax check after editing inline scripts:

```powershell
node -e "const fs=require('fs');const h=fs.readFileSync('src/main/resources/static/community.html','utf8');new Function([...h.matchAll(/<script(?:\\s[^>]*)?>([\\s\\S]*?)<\\/script>/gi)].map(m=>m[1]).join('\\n'));console.log('syntax ok')"
```

## Known Frontend Boundaries

- The frontend remains static vanilla JavaScript with repeated page scripts.
- Reactions/comments are not shared between users yet.
- Community image uploads are deferred until backend media storage exists.
- Client-side filtering covers loaded message pages, not the entire unloaded feed.
- Some profile presentation values remain browser-local and should migrate to
  server-backed DTOs in future phases.
