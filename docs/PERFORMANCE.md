# Performance

Two kinds of measurement live here: page loading (F-3, Lighthouse) and API
latency and throughput under load (PF-1). Both are against a local build at
`http://127.0.0.1:8081`; neither is a claim about production hardware.

## PF-1 · API latency and throughput (Phase 6)

Measurement, not optimisation: the harness changes nothing and reports what it
finds. `scripts/perf/seed-requests.js` builds the dataset **through the API**, so
every priority score is the application's own post-V15 model, and
`scripts/perf/load-test.js` drives four endpoints at 1, 10 and 50 concurrent
users in a closed loop (10 s warm-up discarded, 15 s measured per level).

Machine: one laptop running the application, PostgreSQL 17 and the load
generator at once, so these are *relative* figures — the comparison between
endpoints and between dataset sizes is what they support, not an absolute
capacity claim. The application ran with `RATELIMIT_ENABLED=false`: the limiter
allows 100 API and 5 auth calls a minute per client, so with it on the run would
measure the limiter. What the limiter does is measured by the S-9 acceptance
check.

Raw output: [`evaluation/pf-1/perf-10k.json`](evaluation/pf-1/perf-10k.json) and
[`evaluation/pf-1/perf-121k.json`](evaluation/pf-1/perf-121k.json).

### At 10,000 seeded requests — the plan's target

| Endpoint | Users | p50 | p95 | p99 | Throughput |
|---|---:|---:|---:|---:|---:|
| `POST /api/auth/login` | 1 | 63.7 ms | 78.5 ms | 80.6 ms | 15 req/s |
| | 10 | 75.7 ms | 85.6 ms | 96.3 ms | 131 req/s |
| | 50 | 357.8 ms | 376.8 ms | 394.6 ms | 139 req/s |
| `GET /api/v1/admin/dashboard/ranked?size=20` | 1 | 15.6 ms | 18.1 ms | 23.2 ms | 67 req/s |
| | 10 | 9.0 ms | 14.0 ms | 15.9 ms | 1,040 req/s |
| | 50 | 40.9 ms | 60.7 ms | 78.0 ms | 1,186 req/s |
| `GET /api/admin/stats` | 1 | 15.7 ms | 20.9 ms | 24.0 ms | 63 req/s |
| | 10 | 14.3 ms | 18.3 ms | 20.8 ms | 688 req/s |
| | 50 | 74.0 ms | 104.0 ms | 123.7 ms | 664 req/s |
| `POST /api/help-requests` | 1 | 15.5 ms | 17.4 ms | 18.4 ms | 67 req/s |
| | 10 | 5.1 ms | 8.2 ms | 10.8 ms | 1,855 req/s |
| | 50 | 19.8 ms | 36.1 ms | 47.2 ms | 2,286 req/s |

No errors at any level. Seeding itself created 10,000 requests through the API
in 11.2 s (893 a second), each one validated, scored, inserted and put through
automatic matching.

**Login is the slow path, and deliberately so.** At 60–75 ms a call it is an
order of magnitude slower than anything else, and its ceiling of ~140 requests a
second is what the whole platform's authentication can do on this machine. The
cost is bcrypt at the Spring Security default of 10 rounds, which is a security
control, not a defect: it is what makes an offline attack on a stolen hash
expensive. Lowering it would speed this line up and weaken the control, so it
stays. Everything downstream is stateless JWT verification, which is why the
other three endpoints are 4–15× faster.

**Submission is fast because matching is cheap.** A submitted request is
validated, scored, inserted and offered to the nearest eligible provider in
about 5 ms of server time at 10 concurrent users. The auto-matching step
(`AutomaticAssignmentService`) does not scan the request table — it ranks
providers, of which there are few — so the write path is flat in the number of
requests.

**The ranked queue is paged and stays paged.** 9–41 ms for a page of 20 with
provider suggestions over 10,000 pending requests, at over a thousand a second.

### At 121,495 requests — what changes with scale

The submission scenario leaves the dataset twelve times larger, which is a free
scaling experiment. Re-measuring the two read endpoints on it:

| Endpoint | Users | p50 at 10 k | p50 at 121 k | Throughput at 10 k | at 121 k |
|---|---:|---:|---:|---:|---:|
| ranked queue | 1 | 15.6 ms | 14.3 ms | 67 req/s | 62 req/s |
| | 50 | 40.9 ms | 90.6 ms | 1,186 req/s | 547 req/s |
| admin stats | 1 | 15.7 ms | 79.9 ms | 63 req/s | 12 req/s |
| | 50 | 74.0 ms | 685.9 ms | 664 req/s | 70 req/s |

**The ranked queue scales; the statistics endpoint does not.** The queue is a
page of 20 rows off an index, so twelve times the data costs it about twice the
latency under load and nothing at all for a single user. `GET /api/admin/stats`
is thirteen aggregate queries that each count the whole table — index-only scans
(3–5 ms each at 10 k), but scans — so its cost grows with the row count: five
times the single-user latency and nine times the loss of throughput for twelve
times the data.

At the scale the plan specifies it is 15.7 ms and needs nothing. The measurement
says where it would need something: past roughly 10⁵ requests the dashboard
should either cache its counters or maintain them incrementally, the way AGG-1
maintains the provider counters. That is recorded in `FUTURE_WORK.md` rather
than built, because optimising an endpoint that answers in 16 ms at the target
scale would be optimising the wrong thing.

### What the measurement changed

One thing, in the harness rather than the platform. The first run put the
submission scenario first, so the two read scenarios afterwards were measuring a
database the write scenario had already inflated — `GET /api/admin/stats` looked
like a 60 ms endpoint when at the stated dataset size it is a 16 ms one, and a
second run against the same database showed 2.1 s because it was then measuring
150,000 rows. The harness now runs the read scenarios first, records the request
count before and after every scenario, and prints a line when a scenario grows
the dataset. **A load test that mutates its own dataset has to say so, or its
numbers belong to a system nobody specified.**

Nothing in the application was optimised: at the specified scale no endpoint was
slow enough to justify it, and the scaling curve above says which one to look at
first when that changes.

### Reproducing

```bash
PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe" scripts/gate/fresh-db.sh nidaa_perf
python scripts/gate/smtp-sink.py 1025 &
DB_URL="jdbc:postgresql://localhost:5432/nidaa_perf?stringtype=unspecified" \
RATELIMIT_ENABLED=false MAIL_HOST=127.0.0.1 MAIL_PORT=1025 MAIL_SSL=false MAIL_AUTH=false \
"$JAVA_HOME/bin/java" -jar target/platform-1.0.0.jar &

BASE=http://127.0.0.1:8081 DB=nidaa_perf node scripts/perf/seed-requests.js --count 10000
# the seeder prints the accounts the load test needs; then:
WARMUP=10 SECONDS=15 node scripts/perf/load-test.js --out perf-10k.json --label "10,000 requests"
# and, on the larger database the run leaves behind:
node scripts/perf/load-test.js --out perf-121k.json --only ranked-queue,admin-dashboard
```

Latency figures vary with the machine; the shape of the table — login an order of
magnitude above the rest, statistics growing with the row count, the queue and
the write path flat — is what should reproduce.

## F-3 · Image optimisation (Phase 4)

`index.html` shipped nine images as full-size PNG/JPEG (1408×768 and 1376×768
files displayed at 84×46 to 568×310 CSS pixels). They were converted to WebP at
twice their CSS display size, given explicit `width`/`height` attributes so
layout is reserved before they load, and `loading="lazy"` where they sit below
the fold. The hero image is the page's largest-contentful-paint element and is
therefore *not* lazy-loaded; it gets `fetchpriority="high"` instead.
`nidaa-logo.png` (1.0 MB) was referenced by nothing and was deleted.

| File | Before | After |
|---|---|---|
| `nidaa-logo-nobg.jpg` → `nidaa-logo.webp` | 1,090 KB, 1408×768 | 10.7 KB, 293×160 |
| `nidaa-hero.png` → `nidaa-hero.webp` | 1,198 KB, 1408×768 | 26.8 KB, 1140×622 |
| `nidaa-logo.png` (unreferenced) | 976 KB | deleted |
| `images/help-types/*-help.png` ×6 → `.webp` | 11,257 KB, ~1376×768 each | 133.4 KB, 440×330 each |
| **Total image bytes on `index.html`** | **13,879 KB** | **178 KB** (mobile: 138 KB, two cards below the fold not fetched) |

The plan's target was the three root images under 150 KB combined: they are
37.5 KB. The six help-type images were not in the plan's inventory but were
78 % of the page weight, so they received the same treatment.

### Lighthouse, `index.html`

| Metric | Mobile before | Mobile after | Desktop before | Desktop after |
|---|---|---|---|---|
| Performance score | 64 | 98 | 67 | 82 |
| Largest Contentful Paint | 55.7 s | 2.1 s | 55.7 s | 2.0 s |
| First Contentful Paint | 1.7 s | 1.8 s | 1.7 s | 1.7 s |
| Speed Index | 23.1 s | 2.0 s | 1.7 s | 1.7 s |
| Total bytes | 13,843 KB | 428 KB | 13,840 KB | 465 KB |
| Image bytes | 13,553 KB | 138 KB | 13,553 KB | 178 KB |

What remains on desktop (82) is not images: the render-blocking Google Fonts
and Font Awesome stylesheets loaded from CDNs, which F-5's CSP work does not
change. Recorded here so the next measurement has a baseline.

Raw Lighthouse output: `scripts/gate/` reproduces the run with
`node scripts/gate/lighthouse.js http://127.0.0.1:8081/index.html <label>`
(needs `lighthouse` and `puppeteer-core` resolvable, see `scripts/gate/README.md`).
