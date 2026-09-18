# EV-1 — Matching strategy experimental study

*A comparative analysis of four assignment strategies for humanitarian help
requests under varying load and provider density. Written for the evaluation
chapter of the thesis; every number below comes from one reproducible run of
`MatchingStudyMain` (base seed 20260917, 10 seeded repetitions per cell,
checksum `9c0f4b2f6e68ef7d`), whose output is committed next to this file in
[`ev-1/`](ev-1/).*

**The study measures the platform as it behaves after GAP-1 and GAP-2.** Those
two changes altered what automatic matching does — a provider can now decline,
and a request nobody took is retried every 30 minutes in priority order — so
results from before them would describe a system that no longer exists. §2.1
says exactly how the simulation mirrors the code.

## 1. Question

Under humanitarian constraints, which assignment strategy best balances
**urgency** (critical requests are reached quickly), **fairness** (no provider
and no region carries a disproportionate share) and **efficiency** (little time
is lost travelling, so more requests are served) — and how does the answer
change with the **load** on the system and the **density** of providers?

The platform implements four strategies as `MatchingStrategy` beans and already
compared them on the pending queue as a snapshot (`MatchingEvaluationService`,
`GET /api/v1/admin/evaluation`). A snapshot cannot show waiting time, because
waiting is what happens *between* snapshots. This study puts the same four
classes into a discrete-event simulation of dispatching and measures what each
does to the people waiting.

## 2. Method

### 2.1 The simulation, and why it matches the platform

`MatchingSimulation` models one region over a 72-hour horizon. Every mechanism
below exists in the code it is named after:

| In the simulation | In the platform |
|---|---|
| On arrival, a request is offered to **one** provider, chosen by the strategy among those free at that instant. No ordering happens: there is one request. | `HelpRequestService.createRequest` calls `AutomaticAssignmentService.assignNearestProvider` |
| Every **30 minutes** the pending queue is ranked by the strategy and placed greedily against the free providers. **This is the only place the priority model orders anything.** | `StaleRequestScheduler.retryPending`, `findUnassignedPendingByPriority` (GAP-2) |
| A provider becoming free changes nothing until the next sweep. | nothing in the platform reacts to a release; the sweep is what notices |
| A provider may **decline** (10 % of offers): the request returns to the queue at once, the provider stays free, and that pair is never offered again. After three declines the request is counted as escalated to a human. | `RequestDeclineService`, `AutomaticAssignmentService.ProviderExclusions`, `AttentionService` (GAP-1) |
| Service costs the request's handling time (1–3 h) plus the round trip at 40 km/h; the provider returns home. | not modelled in the platform — the study's own assumption (§5) |

Whether a given provider declines a given request is a deterministic function of
the dataset seed and the two ids, not of the order a strategy happened to try
them in. Two strategies therefore meet **the same refusals on the same dataset**,
which is what keeps the paired design honest.

After the last arrival the sweeps continue until the queue empties, so waiting
times are not censored — except for a request every eligible provider has
refused, which no later sweep can place; it is reported as unassigned instead of
looping for ever. In this run that share was 0 % everywhere.

The strategies are the production classes:

| Strategy | Ranks the queue by | Chooses the provider by |
|---|---|---|
| `FIFO` | arrival time | first free provider (the interface default); the free list is ordered by how long a provider has been idle |
| `WEIGHTED_SCORING` | the priority score (urgency, vulnerability flags, household size, capped aging) | first free provider, as above |
| `GEO_NEAREST` | distance to the nearest free provider, closest first | the nearest free provider |
| `MULTI_OBJECTIVE_OPTIMIZATION` | priority score ×2, an urgency bonus, a regional-balance term that decays as a region is picked again, and a distance term | the nearest free provider |

`GEO_NEAREST` was added for this study as the plan's fourth level: the efficiency
pole, minimising travel greedily and ignoring urgency and arrival order entirely.

### 2.2 Dataset generation

`SyntheticDataGenerator` is the parameterised generator behind the dev seeder
(`DataSeeder`, still its 500 demo requests from seed 42) and the study. A
`DatasetSpec` fixes the seed, the number of requests, the horizon, the
settlements and their share of demand, the providers per settlement, the urgency
mix, the vulnerability-flag rates, the household-size range and the handling-time
range. Every value is drawn from one seeded `Random` in a fixed order, so the
same spec produces the same dataset on any machine.

The region is three settlements of one oblast 60–85 km apart — Central (55.60 N,
37.40 E), North (56.30 N, 37.60 E) and East (55.70 N, 38.40 E), each a square of
0.3° (about 19 × 33 km) — placed so that each falls in its own one-degree cell of
`RequestRegionResolver`, the resolver the platform and the multi-objective
strategy use. Demand is uneven on purpose (50 / 30 / 20 % of requests) while
providers are spread evenly, so the central settlement is under-provisioned
relative to its demand. Urgency follows the plan's weighting (10 % CRITICAL,
25 % HIGH, 40 % MEDIUM, 25 % LOW); 30 % of requests have children, 20 % elderly,
15 % disabled; households are 1–10 people.

**All datasets are generated after V15 and none passes through the database.**
Every priority score a strategy sees is computed by `PriorityScoreService`, the
post-V15 Java model, so no result mixes trigger-computed and Java-computed
scores. The weights it uses are defended in [`../SCORING.md`](../SCORING.md) and
varied in §6.

### 2.3 Design

A full factorial of **strategy (4) × load (3) × provider density (2)** with
**10 seeded repetitions per cell**: 240 runs.

| Factor | Levels |
|---|---|
| Strategy | FIFO, WEIGHTED_SCORING, GEO_NEAREST, MULTI_OBJECTIVE_OPTIMIZATION |
| Load (arrivals over 72 h) | LOW 1/h (72 requests), MEDIUM 2/h (144), HIGH 4/h (288) |
| Provider density | SPARSE 2 per settlement (6 providers), DENSE 6 per settlement (18) |

The design is **paired**: one dataset per (load, density, repetition) from seed
`20260917 + 1000·load + 100·density + repetition`, given to all four strategies,
so a difference between strategies is never a difference between datasets. Each
cell is reported as the mean and the sample standard deviation over its 10
repetitions; where the text compares two strategies it also says in how many of
the 10 paired datasets the difference kept its sign.

Against capacity: with a mean handling time of 2 h and the round trips the
strategies actually produce (§4), six providers serve between 1.3 and 1.9
requests an hour, so SPARSE is under-provisioned at MEDIUM load and heavily so at
HIGH; eighteen providers only begin to queue at HIGH load.

### 2.4 Dependent variables

| Variable | Definition |
|---|---|
| CRITICAL mean and p95 waiting time (h) | arrival to assignment over CRITICAL requests; p95 is the nearest-rank percentile |
| Urgent within 6 h (%) | share of HIGH and CRITICAL requests assigned within 6 hours — a request nobody took counts as missing the window |
| p95 waiting time, all requests (h) | the tail of everyone's wait — what prioritisation costs the requests it demotes |
| Provider utilisation Gini | Gini of busy hours across providers; 0 = every provider equally loaded |
| Mean travel distance (km) | straight-line distance of an assignment |
| Completed within the horizon (%) | share of all requests whose delivery finished inside the 72 hours — the throughput measure |
| Regional fairness (Jain) | Jain's index over the per-settlement share assigned within 24 h |
| Escalated (%) | share refused by three providers, which in the platform raises `needs_attention` |
| Never taken (%) | share every eligible provider refused; 0 in this run, reported because the model can produce it |

## 3. Results

The full table is [`ev-1/results.md`](ev-1/results.md) (`results.csv` and
`results.json` carry the same numbers machine-readably; `runs.csv` has all 240
runs). Figures: [1](ev-1/figure-1-critical-mean-wait-h.svg) CRITICAL mean waiting
time, [2](ev-1/figure-2-urgent-within-6h-pct.svg) urgent within 6 h,
[3](ev-1/figure-3-utilisation-gini.svg) utilisation Gini,
[4](ev-1/figure-4-mean-distance-km.svg) travel distance,
[5](ev-1/figure-5-trade-off.svg) the trade-off scatter.

### 3.1 Sparse provision (6 providers)

| Load | Strategy | CRITICAL wait, mean (h) | CRITICAL wait, p95 (h) | Urgent ≤ 6 h (%) | p95 wait, all (h) | Distance (km) | Completed in 72 h (%) | Gini | Regional Jain |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| LOW | FIFO | 1.07 ± 0.61 | 3.78 ± 2.51 | 97.9 ± 4.35 | 4.52 ± 2.58 | 53.4 ± 2.28 | 92.1 ± 5.20 | 0.03 ± 0.01 | 1.00 |
| LOW | Weighted | 0.54 ± 0.54 | 1.80 ± 1.29 | 100 | 6.39 ± 3.82 | 54.5 ± 3.28 | 91.4 ± 4.80 | 0.02 ± 0.01 | 1.00 |
| LOW | Geo-nearest | 0.32 ± 0.34 | 1.17 ± 1.23 | 100 | 1.92 ± 1.50 | 31.0 ± 4.92 | 95.6 ± 2.99 | 0.07 ± 0.02 | 1.00 |
| LOW | Multi-objective | 0.24 ± 0.22 | 1.11 ± 1.02 | 100 | 2.29 ± 1.97 | 32.3 ± 5.70 | 95.1 ± 3.66 | 0.07 ± 0.02 | 1.00 |
| MEDIUM | FIFO | 21.3 ± 6.94 | 42.3 ± 5.19 | 30.9 ± 6.39 | 42.6 ± 4.48 | 51.8 ± 3.27 | 59.2 ± 2.15 | 0.01 | 0.98 ± 0.01 |
| MEDIUM | Weighted | 2.38 ± 1.40 | 12.8 ± 9.05 | 67.5 ± 7.98 | 77.9 ± 7.55 | 51.6 ± 2.37 | 58.9 ± 1.93 | 0.01 | 0.98 ± 0.02 |
| MEDIUM | Geo-nearest | 8.37 ± 5.17 | 35.2 ± 17.3 | 69.1 ± 5.80 | 40.6 ± 8.61 | 28.5 ± 2.38 | 79.5 ± 2.97 | 0.01 | 0.99 ± 0.01 |
| MEDIUM | Multi-objective | 0.96 ± 0.46 | 2.77 ± 1.13 | 89.9 ± 9.05 | 55.9 ± 9.50 | 35.1 ± 1.59 | 68.1 ± 2.35 | 0.01 | 0.99 ± 0.01 |
| HIGH | FIFO | 80.8 ± 8.55 | 155 ± 7.07 | 19.1 ± 3.06 | 155 ± 3.76 | 54.0 ± 1.68 | 29.8 ± 1.35 | 0.01 | 0.97 ± 0.02 |
| HIGH | Weighted | 22.7 ± 4.86 | 79.3 ± 21.5 | 31.8 ± 5.19 | 188 ± 3.86 | 53.8 ± 2.06 | 29.5 ± 0.99 | 0.01 | 0.97 ± 0.04 |
| HIGH | Geo-nearest | 44.9 ± 7.92 | 122 ± 14.4 | 32.1 ± 4.36 | 127 ± 4.07 | 24.7 ± 1.38 | 40.8 ± 0.97 | 0.01 | 0.97 ± 0.02 |
| HIGH | Multi-objective | 3.12 ± 1.87 | 11.8 ± 8.14 | 44.4 ± 6.67 | 136 ± 4.35 | 27.6 ± 1.45 | 33.7 ± 1.19 | 0.01 | 0.98 ± 0.01 |

### 3.2 Dense provision (18 providers)

| Load | Strategy | CRITICAL wait, mean (h) | CRITICAL wait, p95 (h) | Urgent ≤ 6 h (%) | p95 wait, all (h) | Distance (km) | Completed in 72 h (%) | Gini | Regional Jain |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| LOW | FIFO | 0.07 ± 0.07 | 0.31 ± 0.26 | 100 | 0.24 ± 0.14 | 54.2 ± 2.99 | 92.4 ± 4.10 | 0.09 ± 0.02 | 1.00 |
| LOW | Weighted | 0.07 ± 0.07 | 0.31 ± 0.26 | 100 | 0.24 ± 0.14 | 54.2 ± 2.99 | 92.4 ± 4.10 | 0.09 ± 0.02 | 1.00 |
| LOW | Geo-nearest | 0.01 ± 0.03 | 0.10 ± 0.15 | 100 | 0.22 ± 0.14 | 7.02 ± 0.64 | 95.4 ± 3.21 | 0.37 ± 0.06 | 1.00 |
| LOW | Multi-objective | 0.01 ± 0.03 | 0.10 ± 0.15 | 100 | 0.22 ± 0.14 | 7.02 ± 0.64 | 95.4 ± 3.21 | 0.37 ± 0.06 | 1.00 |
| MEDIUM | FIFO | 0.03 ± 0.03 | 0.29 ± 0.24 | 100 | 0.25 ± 0.08 | 54.4 ± 2.27 | 92.9 ± 1.96 | 0.05 ± 0.01 | 1.00 |
| MEDIUM | Weighted | 0.03 ± 0.03 | 0.29 ± 0.24 | 100 | 0.24 ± 0.08 | 54.7 ± 2.42 | 92.9 ± 1.96 | 0.05 ± 0.01 | 1.00 |
| MEDIUM | Geo-nearest | 0.04 ± 0.07 | 0.28 ± 0.39 | 100 | 0.24 ± 0.12 | 8.80 ± 0.89 | 96.1 ± 1.19 | 0.27 ± 0.04 | 1.00 |
| MEDIUM | Multi-objective | 0.04 ± 0.07 | 0.28 ± 0.39 | 100 | 0.24 ± 0.12 | 8.80 ± 0.89 | 96.1 ± 1.19 | 0.27 ± 0.04 | 1.00 |
| HIGH | FIFO | 3.56 ± 1.74 | 10.5 ± 4.11 | 71.7 ± 17.8 | 11.0 ± 4.00 | 54.1 ± 2.47 | 84.3 ± 3.39 | 0.02 | 1.00 |
| HIGH | Weighted | 0.53 ± 0.23 | 2.18 ± 1.10 | 95.9 ± 3.52 | 19.3 ± 8.01 | 54.0 ± 2.08 | 84.3 ± 3.18 | 0.01 | 1.00 |
| HIGH | Geo-nearest | 0.21 ± 0.20 | 1.28 ± 1.07 | 99.7 ± 0.47 | 1.33 ± 0.61 | 25.5 ± 3.18 | 94.1 ± 2.17 | 0.06 ± 0.01 | 1.00 |
| HIGH | Multi-objective | 0.10 ± 0.04 | 0.59 ± 0.30 | 100 | 1.73 ± 0.77 | 28.0 ± 4.46 | 93.2 ± 2.32 | 0.05 ± 0.02 | 1.00 |

(A standard deviation below 0.005 is omitted; full precision is in
`results.csv`. Escalations after three declines were 0.0–0.3 % of requests
everywhere, and no request went entirely untaken.)

### 3.3 Paired contrasts

Per-dataset differences across the ten paired datasets, and how often the sign
held:

| Cell | Contrast | Difference | Same sign |
|---|---|---:|---:|
| SPARSE / HIGH | CRITICAL mean wait, Multi-objective − FIFO | −77.7 ± 8.9 h | 10 / 10 |
| SPARSE / HIGH | CRITICAL mean wait, Multi-objective − Geo-nearest | −41.8 ± 7.7 h | 10 / 10 |
| SPARSE / HIGH | CRITICAL mean wait, Multi-objective − Weighted | −19.6 ± 5.1 h | 10 / 10 |
| SPARSE / HIGH | completed in 72 h, Geo-nearest − Multi-objective | +7.1 ± 1.7 pp | 10 / 10 |
| SPARSE / HIGH | mean distance, Geo-nearest − Multi-objective | −2.9 ± 1.1 km | 10 / 10 |
| SPARSE / HIGH | p95 wait (all), Geo-nearest − Weighted | −61.3 ± 4.8 h | 10 / 10 |
| SPARSE / MEDIUM | CRITICAL mean wait, Multi-objective − Geo-nearest | −7.4 ± 4.8 h | 10 / 10 |
| SPARSE / MEDIUM | completed in 72 h, Geo-nearest − Weighted | +20.6 ± 3.4 pp | 10 / 10 |
| SPARSE / MEDIUM | urgent within 6 h, Multi-objective − Weighted | +22.5 ± 10.9 pp | 10 / 10 |
| DENSE / LOW | utilisation Gini, FIFO − Geo-nearest | −0.28 ± 0.06 | 10 / 10 |
| DENSE / LOW | mean distance, Geo-nearest − FIFO | −47.2 ± 3.4 km | 10 / 10 |
| DENSE / HIGH | CRITICAL mean wait, Weighted − FIFO | −3.0 ± 1.8 h | 10 / 10 |

## 4. Discussion

**The trade-off is real, and it is between urgency and throughput, mediated by
distance.** Under sparse provision at high load the multi-objective strategy
reaches CRITICAL requests in 3.1 h on average against geo-nearest's 44.9 h and
FIFO's 80.8 h — in every one of the ten paired datasets — while geo-nearest
completes 40.8 % of all requests inside the horizon against the multi-objective's
33.7 %, also in every dataset. The mechanism is capacity: a provider who ignores
geography spends 2.7 h of every 4.7 h job travelling (mean trip 54 km), so six
providers serve 1.3 requests an hour against an arrival rate of 4; nearest-first
dispatch (25 km, 3.2 h a job) serves 1.9. Every hour of travel saved is an hour
of service gained, and under overload the strategy that saves the most travel
serves the most people — just not the most urgent ones first. Figure 5 shows the
frontier: no strategy sits in the lower-left corner.

**Prioritisation without a distance-aware provider choice is the weakest
combination.** Weighted scoring buys shorter CRITICAL waits than FIFO (22.7 h
against 80.8 h at HIGH/SPARSE) at the full travel cost, so its throughput is
FIFO's (29.5 % against 29.8 %) and its tail is the longest of anyone: p95 waiting
over all requests is 188 h, 61 h worse than geo-nearest, because the aging bonus
is capped and a demoted request waits until the drain. The multi-objective
strategy, which ranks by priority *and* picks the nearest provider, is better
than weighted scoring on both sides at once — 22.5 percentage points more urgent
requests served within six hours at MEDIUM/SPARSE, and 9 points more throughput.
That is the study's clearest practical finding: **the ordering rule and the
provider-selection rule are separable, and the second matters more than the
literature on queue discipline would suggest.**

**The answer changes with load.** At LOW load nothing queues for long: all four
strategies reach CRITICAL requests inside about an hour, and they differ mainly
in travel (31–32 km for the distance-aware two against 53–55 km) and throughput
(95 % against 92 %). The strategies separate at MEDIUM load under sparse
provision and are an order of magnitude apart at HIGH: 80.8 h of CRITICAL waiting
under FIFO, 3.1 h under the multi-objective rule, on the same requests.

**And with density.** With eighteen providers the queue barely forms: waiting is
under 0.1 h for every strategy at LOW and MEDIUM load, and geo-nearest and
multi-objective become the same policy (identical numbers — with one pending
request the ranking is moot and both take the nearest provider). What remains is
the fairness cost of efficiency: nearest-first concentrates work on the providers
who live near the demand (utilisation Gini 0.37 against 0.09, in all ten
datasets) to save 47 km a job. At DENSE/HIGH a queue appears and ordering matters
again, but in hours rather than days.

**What GAP-1 and GAP-2 cost and buy.** Two mechanisms the platform now has are
visible in these numbers. The 30-minute sweep means a provider who becomes free
waits, on average, a quarter of an hour before anything is offered to them; at
LOW/DENSE, where nothing else delays anyone, that is most of the 0.07 h average
CRITICAL wait. Declines at 10 % cost little: escalations after three refusals
stayed at 0.0–0.3 % of requests and no request went untaken, because the rematch
finds someone else. The sweep interval is therefore the cheaper thing to tune
than the decline policy — halving it would halve that residual wait, at the price
of more matching work.

**Regional fairness moved little.** Jain's index over 24-hour coverage stays at
1.00 wherever there is no backlog and falls only under sparse provision at high
load, to 0.97–0.98, with the multi-objective strategy highest — its regional term
doing what it was designed for. The index is insensitive when every region is
served equally badly, which is why it is a secondary metric here.

**What this means for the platform.** The multi-objective strategy is the right
default for the ranked queue humans work through and for the retry sweep: it
keeps critical requests at the top and its distance term stops the queue from
sending responders across the region. Under overload, though, the study says
plainly that throughput — and therefore distance — dominates, so a platform that
must serve the most people should reach for the distance-aware rule with an
explicit urgency override rather than a score that always wins on urgency. The
weighted score on its own, which is what the pre-remediation platform used
everywhere, is the one combination the data argue against.

## 5. Threats to validity

- **Synthetic data.** Arrivals are uniform over the horizon; real demand clusters
  after events and by time of day. Urgency, vulnerability flags and household
  size are drawn independently of each other and of location. The urgency mix is
  the plan's assumption, not an observed distribution.
- **No real travel times.** Distance is straight-line at a constant 40 km/h;
  roads, traffic and the difference between a city street and a regional road are
  absent. Providers return home after every job rather than chaining them. Both
  shrink the absolute distance effect without changing its direction.
- **The decline rate is an assumption.** 10 % of offers, independent of the
  provider, the request and how busy the day has been. Real refusals correlate
  with distance and fatigue, which would hurt the distance-blind strategies more
  than the numbers here show.
- **No provider drop-out, no resource filter.** Every provider is always
  available and can serve any request: the production resource-eligibility filter
  (`ProviderResourceService`) and organizations are not modelled. Real provision
  is patchier, which would widen the gaps rather than close them.
- **No time-of-day effects**, and handling time is uniform 1–3 h independent of
  the request: a family of ten does not take longer than one person.
- **Ranking and provider selection are confounded** in two of the four
  strategies: FIFO and WEIGHTED_SCORING are evaluated with the interface's
  default provider choice, GEO_NEAREST and MULTI_OBJECTIVE_OPTIMIZATION with the
  nearest free provider. §4 attributes effects to the rule that plausibly caused
  them, and the MEDIUM/SPARSE comparison of weighted scoring against
  multi-objective isolates it as well as this design can; a 2 × 2 design
  (ordering rule × selection rule) would separate them properly and is the first
  thing to do next.
- **One geometry.** Three settlements, one demand split, providers spread evenly.
  The regional metric in particular depends on that choice.
- **Windows and indices.** "Urgent within 6 h", "coverage within 24 h" and the
  72-hour horizon are choices; Jain's index is blind to regions that are
  uniformly badly served; the Gini is over busy hours, so a provider who travels
  far counts as "utilised" while delivering less.
- **The drain.** Serving the queue to the end after the last arrival gives
  complete waiting-time distributions, but a real system under such overload
  would see cancellations and re-filed requests, which shorten the measured tail.
- **Sample size.** Ten repetitions per cell are enough for the effects reported
  here — every headline contrast held in 10 of 10 paired datasets — but not for
  the small differences between neighbouring cells.
- **No production traffic.** The platform has no operational history to validate
  the model against. `FUTURE_WORK.md` records the replay of real requests and the
  2 × 2 design as the next steps.

## 6. Sensitivity to the priority weights

The weights in `PriorityScoreService` are reasoned rather than measured
(`docs/SCORING.md` §5), so the study re-runs the same seeded datasets with them
changed and asks whether its conclusions move. Four variants, on sparse provision
where a queue actually forms:

| Variant | What changes |
|---|---|
| `production` | the weights the platform runs |
| `levelled-vulnerability` | children, elderly and disability all +10 — the challenged 15-against-10 gap removed |
| `doubled-urgency` | urgency 80/60/40/20, everything else unchanged |
| `uncapped-waiting` | ageing never stops, so waiting can eventually overtake urgency |
| `no-vulnerability` | children, elderly and disability count for nothing |

The full comparison is [`ev-1/sensitivity.md`](ev-1/sensitivity.md), with every
cell mean in [`ev-1/sensitivity.csv`](ev-1/sensitivity.csv). The result:

- **Every headline conclusion survives every variant.** The multi-objective
  strategy has the shortest CRITICAL waiting time in all three sparse cells under
  all five weightings; geo-nearest has the highest throughput and the shortest
  overall p95 in all of them (one exception: under `uncapped-waiting` at
  HIGH/SPARSE the multi-objective strategy takes the p95 as well).
- **The weights move the numbers, not the ranking.** Doubling urgency or removing
  the vulnerability factors changes the multi-objective strategy's CRITICAL
  waiting time by a fraction of an hour at MEDIUM/SPARSE and leaves the ordering
  between strategies intact.
- **Where the weights do decide something, it is small and at low load.** At
  LOW/SPARSE the strategy with the best "urgent within 6 h" flips between
  weighted scoring and geo-nearest depending on the variant — a cell where all
  four strategies are within a couple of percentage points of each other anyway.

The comparison of *strategies* is therefore robust to the one part of the model a
reviewer is most likely to dispute. What the weights change is *which requests
wait* inside a strategy, which is a fairness question the sensitivity table
cannot settle and `docs/SCORING.md` argues on its own terms.

## 7. Reproducing the study

```bash
./mvnw -q -DskipTests package
java -cp target/platform-1.0.0.jar \
     -Dloader.main=com.humanitarian.platform.evaluation.MatchingStudyMain \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     --out docs/evaluation/ev-1 --reps 10 --seed 20260917
```

About five minutes, no database and no Spring context. It prints one line per
dataset, then one per sensitivity cell, and ends with `checksum
9c0f4b2f6e68ef7d`. Every file in `ev-1/` is regenerated byte for byte and
`git status` stays clean afterwards. A different `--seed` gives different
datasets and, within the standard deviations reported, the same conclusions.
`MatchingStudyTest` runs a reduced design twice in the build and asserts the two
agree to the last digit.

Code: `com.humanitarian.platform.evaluation` (`DatasetSpec`,
`SyntheticDataGenerator`, `SyntheticDataset`, `SimulationClock`,
`MatchingSimulation`, `RunMetrics`, `MatchingStudy`, `StudyReport`, `SvgCharts`,
`MatchingStudyMain`), `service.matching.GeoNearestStrategy`, and the `Clock` and
`PriorityWeights` constructors of `service.PriorityScoreService`. Tests:
`SyntheticDataGeneratorTest`, `MatchingSimulationTest`, `MatchingStudyTest`,
`GeoNearestStrategyTest`, `PriorityScoreServiceTest.agingFollowsTheInjectedClock`.
