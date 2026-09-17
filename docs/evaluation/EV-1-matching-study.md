# EV-1 — Matching strategy experimental study

*A comparative analysis of four assignment strategies for humanitarian help
requests under varying load and provider density. Written for the evaluation
chapter of the thesis; the numbers, tables and figures come from one
reproducible run of `MatchingStudyMain` (base seed 20260917, 10 seeded
repetitions per cell, checksum `5e46688a92554258`), whose output is committed
next to this file in [`ev-1/`](ev-1/).*

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
classes into a discrete-event simulation of dispatching, where requests arrive
over time and providers become free again, and measures what each strategy does
to the people waiting.

## 2. Method

### 2.1 The simulation

`MatchingSimulation` is a discrete-event model of one region over a 72-hour
horizon.

- **Arrivals.** Requests arrive at their `createdAt`, spread uniformly over the
  horizon at the cell's arrival rate.
- **Providers.** Volunteers start free at a home location. A provider serving a
  request is busy for the request's *handling time* (uniform 1–3 h) plus the
  *round trip* between home and the request at 40 km/h (straight-line Haversine
  distance), after which they are free again at home.
- **Decision points.** Whenever a request arrives or a provider becomes free,
  the strategy ranks the pending queue against the list of free providers, the
  first request it can staff is assigned to the provider it selects, and this
  repeats until the queue or the free list is empty. Ranking is recomputed after
  every assignment.
- **Drain.** After the last arrival the queue is served to the end, so every
  request is assigned and no waiting time is censored. Throughput is measured
  separately, as the share of requests whose delivery finished inside the
  72-hour horizon.
- **Clock.** The priority model (`PriorityScoreService`) ages requests by hours
  waited; the simulation drives it through an injected `Clock`, so the model
  sees simulated time, not the wall clock. The production application uses the
  same class on the system clock.

The strategies are the production classes, not re-implementations:

| Strategy | Ranks the queue by | Chooses the provider by |
|---|---|---|
| `FIFO` | arrival time | first free provider (the interface default) — the simulation orders the free list by how long a provider has been idle |
| `WEIGHTED_SCORING` | the priority score (urgency, vulnerability flags, household size, capped aging) | first free provider, as above |
| `GEO_NEAREST` | distance to the nearest free provider, closest first | the nearest free provider |
| `MULTI_OBJECTIVE_OPTIMIZATION` | priority score ×2, an urgency bonus, a regional-balance term that decays as a region is picked again, and a distance term | the nearest free provider |

`GEO_NEAREST` was added for this study as the fourth level (the plan's
"geo-nearest"): it is the efficiency pole, minimising travel greedily and
ignoring urgency and arrival order entirely. In production, automatic assignment
on arrival picks the nearest resource-eligible provider
(`AutomaticAssignmentService.assignNearestProvider`) and the ranked queue is what
human responders browse; the study evaluates the strategies as defined, and
§5 names the confound this creates.

### 2.2 Dataset generation

`SyntheticDataGenerator` is the parameterised generator behind the dev seeder
(`DataSeeder`, which still inserts its 500 demo requests from seed 42) and the
study. A `DatasetSpec` fixes the seed, the number of requests, the horizon, the
settlements and their share of demand, the number of providers per settlement,
the urgency mix, the vulnerability-flag rates, the household-size range and the
handling-time range. Every value is drawn from one seeded `Random` in a fixed
order, so the same spec produces the same dataset on any machine.

The study's region is three settlements of one oblast 60–85 km apart — Central
(55.60 N, 37.40 E), North (56.30 N, 37.60 E) and East (55.70 N, 38.40 E), each a
square of 0.3° (about 19 × 33 km) — placed so that each falls in its own
one-degree cell of `RequestRegionResolver`, the resolver the application and the
multi-objective strategy use for regions. Demand is uneven on purpose, 50 / 30 /
20 % of requests, while providers are spread evenly, so the central settlement
is under-provisioned relative to its demand. Urgency follows the plan's
weighting (10 % CRITICAL, 25 % HIGH, 40 % MEDIUM, 25 % LOW); 30 % of requests
have children, 20 % elderly, 15 % disabled; households are 1–10 people.

**All datasets are generated after V15 and none passes through the database.**
Every priority score a strategy sees is computed by `PriorityScoreService`, the
post-V15 Java model; no trigger-computed `priority_score` exists anywhere in the
study, so no result mixes the two.

### 2.3 Design

A full factorial of **strategy (4) × load (3) × provider density (2)** with
**10 seeded repetitions per cell**, 240 runs in all.

| Factor | Levels |
|---|---|
| Strategy | FIFO, WEIGHTED_SCORING, GEO_NEAREST, MULTI_OBJECTIVE_OPTIMIZATION |
| Load (arrivals over 72 h) | LOW 1/h (72 requests), MEDIUM 2/h (144), HIGH 4/h (288) |
| Provider density | SPARSE 2 per settlement (6 providers), DENSE 6 per settlement (18 providers) |

The design is **paired**: one dataset is generated per (load, density,
repetition) from seed `20260917 + 1000·load + 100·density + repetition` and the
same dataset is given to all four strategies, so a difference between strategies
is never a difference between datasets. Each cell is reported as the mean and
the sample standard deviation over its 10 repetitions; where the text compares
two strategies it also says in how many of the 10 paired datasets the
difference had the same sign.

To read the load levels against capacity: with a mean handling time of 2 h and
the round trips the strategies actually produce (§4), six providers serve
between 1.3 and 2.2 requests per hour, so SPARSE is under-provisioned at MEDIUM
load and heavily so at HIGH; eighteen providers serve 3.8–6.5 per hour, so DENSE
only begins to queue at HIGH load.

### 2.4 Dependent variables

| Variable | Definition |
|---|---|
| CRITICAL mean and p95 waiting time (h) | from arrival to assignment, over CRITICAL requests; p95 is the nearest-rank percentile |
| Urgent within 6 h (%) | share of HIGH and CRITICAL requests assigned within 6 hours of arrival |
| p95 waiting time, all requests (h) | the tail of everyone's wait — what prioritisation costs the requests it demotes |
| Provider utilisation Gini | Gini coefficient of busy hours across providers; 0 = every provider equally loaded, 1 = one provider does everything |
| Mean travel distance (km) | straight-line distance of an assignment |
| Completed within the horizon (%) | share of requests whose delivery finished inside the 72 hours — the throughput measure |
| Regional fairness (Jain) | Jain's index over the per-settlement share of requests assigned within 24 h; 1 = every settlement served alike |

`runs.csv` also carries the mean wait over all requests and the share of
assignments that crossed settlements, which explains the distance figures.

## 3. Results

The full table with every cell is [`ev-1/results.md`](ev-1/results.md)
(`results.csv` and `results.json` carry the same numbers machine-readably;
`runs.csv` has all 240 runs). The figures are
[figure 1](ev-1/figure-1-critical-mean-wait-h.svg) CRITICAL mean waiting time,
[figure 2](ev-1/figure-2-urgent-within-6h-pct.svg) urgent requests within 6 h,
[figure 3](ev-1/figure-3-utilisation-gini.svg) provider utilisation Gini,
[figure 4](ev-1/figure-4-mean-distance-km.svg) mean travel distance and
[figure 5](ev-1/figure-5-trade-off.svg) the trade-off scatter.

### 3.1 Sparse provision (6 providers)

| Load | Strategy | CRITICAL wait, mean (h) | CRITICAL wait, p95 (h) | Urgent ≤ 6 h (%) | p95 wait, all (h) | Distance (km) | Completed in 72 h (%) | Gini | Regional Jain |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| LOW | FIFO | 0.66 ± 0.57 | 2.36 ± 1.75 | 99.1 ± 2.87 | 3.20 ± 1.64 | 54.2 ± 3.57 | 92.1 ± 4.14 | 0.02 ± 0.01 | 1.00 |
| LOW | Weighted | 0.37 ± 0.23 | 1.54 ± 0.98 | 99.6 ± 1.13 | 4.16 ± 2.67 | 53.8 ± 2.63 | 92.4 ± 3.78 | 0.03 ± 0.01 | 1.00 |
| LOW | Geo-nearest | 0.13 ± 0.13 | 0.66 ± 0.71 | 100 | 1.12 ± 0.82 | 31.0 ± 2.86 | 95.6 ± 2.91 | 0.07 ± 0.02 | 1.00 |
| LOW | Multi-objective | 0.13 ± 0.10 | 0.68 ± 0.64 | 100 | 1.57 ± 1.45 | 32.2 ± 5.02 | 95.0 ± 4.25 | 0.06 ± 0.01 | 1.00 |
| MEDIUM | FIFO | 18.9 ± 5.58 | 34.9 ± 4.86 | 17.4 ± 6.94 | 36.7 ± 2.96 | 52.2 ± 2.41 | 61.8 ± 1.73 | 0.01 | 0.99 |
| MEDIUM | Weighted | 1.44 ± 0.59 | 8.10 ± 5.85 | 74.6 ± 8.35 | 74.4 ± 10.0 | 54.2 ± 2.96 | 60.6 ± 2.45 | 0.01 | 0.99 ± 0.02 |
| MEDIUM | Geo-nearest | 2.81 ± 1.86 | 13.5 ± 13.1 | 88.6 ± 4.14 | 11.5 ± 3.87 | 23.0 ± 2.02 | 90.9 ± 2.75 | 0.01 | 1.00 |
| MEDIUM | Multi-objective | 0.51 ± 0.18 | 1.43 ± 0.49 | 99.4 ± 1.26 | 37.9 ± 10.8 | 32.1 ± 1.78 | 77.4 ± 2.39 | 0.01 | 1.00 |
| HIGH | FIFO | 75.7 ± 8.40 | 145 ± 7.98 | 5.07 ± 0.87 | 144 ± 8.16 | 53.7 ± 3.39 | 30.5 ± 1.62 | 0.00 | 0.96 ± 0.05 |
| HIGH | Weighted | 11.8 ± 2.92 | 36.8 ± 7.19 | 34.7 ± 4.78 | 179 ± 5.18 | 54.0 ± 1.73 | 30.6 ± 0.97 | 0.00 | 0.97 ± 0.03 |
| HIGH | Geo-nearest | 25.9 ± 4.06 | 91.7 ± 9.70 | 49.5 ± 4.61 | 94.8 ± 3.45 | 15.8 ± 0.93 | 59.1 ± 0.78 | 0.01 | 0.94 ± 0.01 |
| HIGH | Multi-objective | 0.71 ± 0.19 | 1.90 ± 0.72 | 76.9 ± 5.48 | 114 ± 3.39 | 22.8 ± 1.37 | 41.4 ± 1.46 | 0.01 | 0.99 ± 0.01 |

### 3.2 Dense provision (18 providers)

| Load | Strategy | CRITICAL wait, mean (h) | CRITICAL wait, p95 (h) | Urgent ≤ 6 h (%) | p95 wait, all (h) | Distance (km) | Completed in 72 h (%) | Gini | Regional Jain |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| LOW | FIFO | 0 | 0 | 100 | 0 | 54.7 ± 3.02 | 92.4 ± 3.42 | 0.08 ± 0.02 | 1.00 |
| LOW | Weighted | 0 | 0 | 100 | 0 | 54.5 ± 2.95 | 92.4 ± 3.42 | 0.09 ± 0.02 | 1.00 |
| LOW | Geo-nearest | 0 | 0 | 100 | 0 | 6.50 ± 0.54 | 95.6 ± 3.13 | 0.39 ± 0.06 | 1.00 |
| LOW | Multi-objective | 0 | 0 | 100 | 0 | 6.50 ± 0.54 | 95.6 ± 3.13 | 0.39 ± 0.06 | 1.00 |
| MEDIUM | FIFO | 0.00 ± 0.01 | 0.06 ± 0.20 | 100 | 0 | 54.4 ± 2.74 | 92.5 ± 1.56 | 0.05 ± 0.01 | 1.00 |
| MEDIUM | Weighted | 0.00 ± 0.01 | 0.06 ± 0.18 | 100 | 0 | 53.3 ± 2.16 | 92.8 ± 1.50 | 0.05 ± 0.01 | 1.00 |
| MEDIUM | Geo-nearest | 0 | 0 | 100 | 0 | 8.40 ± 0.97 | 96.2 ± 1.15 | 0.28 ± 0.04 | 1.00 |
| MEDIUM | Multi-objective | 0 | 0 | 100 | 0 | 8.40 ± 0.97 | 96.2 ± 1.15 | 0.28 ± 0.04 | 1.00 |
| HIGH | FIFO | 2.23 ± 1.13 | 5.31 ± 1.56 | 94.9 ± 6.49 | 5.45 ± 1.63 | 54.2 ± 2.49 | 86.9 ± 3.07 | 0.02 | 1.00 |
| HIGH | Weighted | 0.29 ± 0.10 | 0.96 ± 0.43 | 99.5 ± 0.67 | 13.7 ± 6.57 | 53.6 ± 1.82 | 86.8 ± 2.52 | 0.02 | 1.00 |
| HIGH | Geo-nearest | 0.06 ± 0.07 | 0.43 ± 0.51 | 100 | 0.48 ± 0.24 | 23.9 ± 3.30 | 94.7 ± 2.37 | 0.07 ± 0.01 | 1.00 |
| HIGH | Multi-objective | 0.04 ± 0.04 | 0.25 ± 0.16 | 100 | 0.96 ± 0.46 | 27.3 ± 3.95 | 93.9 ± 2.69 | 0.06 ± 0.02 | 1.00 |

(A standard deviation below 0.005 is omitted; the full precision is in `results.csv`.)

### 3.3 Paired contrasts

Differences across the ten paired datasets, mean ± sd of the per-dataset
difference and the number of datasets in which the sign held:

| Cell | Contrast | Difference | Same sign |
|---|---|---:|---:|
| SPARSE / HIGH | CRITICAL mean wait, Multi-objective − Geo-nearest | −25.2 ± 4.1 h | 10 / 10 |
| SPARSE / HIGH | CRITICAL mean wait, Multi-objective − Weighted | −11.1 ± 2.9 h | 10 / 10 |
| SPARSE / HIGH | completed in 72 h, Geo-nearest − Multi-objective | +17.7 ± 1.8 pp | 10 / 10 |
| SPARSE / HIGH | mean distance, Geo-nearest − Multi-objective | −7.0 ± 0.7 km | 10 / 10 |
| SPARSE / HIGH | p95 wait (all), Geo-nearest − Multi-objective | −18.9 ± 4.7 h | 10 / 10 |
| SPARSE / HIGH | p95 wait (all), Geo-nearest − Weighted | −84.6 ± 6.2 h | 10 / 10 |
| SPARSE / MEDIUM | CRITICAL mean wait, Weighted − Geo-nearest | −1.4 ± 1.8 h | 8 / 10 |
| SPARSE / MEDIUM | urgent within 6 h, Weighted − Geo-nearest | −14.0 ± 11.2 pp | 9 / 10 (Geo-nearest higher) |
| SPARSE / MEDIUM | completed in 72 h, Weighted − Geo-nearest | −30.4 ± 4.0 pp | 10 / 10 |
| SPARSE / MEDIUM | CRITICAL mean wait, Multi-objective − Weighted | −0.9 ± 0.6 h | 10 / 10 |
| DENSE / LOW | utilisation Gini, FIFO − Geo-nearest | −0.30 ± 0.06 | 10 / 10 |
| DENSE / LOW | mean distance, Geo-nearest − FIFO | −48.2 ± 3.2 km | 10 / 10 |
| DENSE / HIGH | CRITICAL mean wait, Weighted − FIFO | −1.9 ± 1.1 h | 10 / 10 |
| SPARSE / HIGH | regional Jain, Geo-nearest − Multi-objective | −0.05 ± 0.01 | 10 / 10 |

## 4. Discussion

**The trade-off is real, and it is between urgency and throughput, mediated by
distance.** Under sparse provision at high load the multi-objective strategy
reaches CRITICAL requests in 0.7 h on average against 25.9 h for geo-nearest and
11.8 h for weighted scoring — in every one of the ten paired datasets — but
geo-nearest completes 59 % of all requests inside the horizon against the
multi-objective's 41 % and weighted scoring's 31 %, again in every dataset. The
mechanism is capacity: a provider who ignores geography spends 2.7 h of every
4.7 h job travelling (mean trip 54 km, two thirds of assignments cross
settlements), so six providers serve 1.3 requests an hour; nearest-first
dispatch (16 km, 12 % cross-settlement) serves 2.2 an hour with the same six
people. Every hour of travel saved is an hour of service gained, and under
overload the strategy that saves the most travel serves the most people —
just not the most urgent ones first. Figure 5 shows the frontier: no strategy
is in the lower-left corner.

**Weighted scoring alone is the weakest way to prioritise.** Ranking by
priority while choosing the first free provider buys short CRITICAL waits (11.8
h at HIGH/SPARSE, 1.4 h at MEDIUM/SPARSE) at the full travel cost, so it has
FIFO's throughput (31 % against FIFO's 31 %) and the longest tail of anyone:
p95 waiting time over all requests is 179 h at HIGH/SPARSE, 85 h more than
geo-nearest, because the aging bonus is capped at 20 points and a LOW request
can never outrank a fresh CRITICAL one, so the demoted requests wait until the
drain. At MEDIUM/SPARSE geo-nearest even beats weighted scoring on the share of
urgent requests reached within six hours (89 % against 75 %, in nine of ten
datasets), despite ignoring urgency: serving everyone faster serves the urgent
faster too. Prioritisation pays only when it is combined with a distance-aware
provider choice, which is what the multi-objective strategy does — 0.5 h
CRITICAL wait at MEDIUM/SPARSE with 77 % completion, against weighted scoring's
1.4 h and 61 %.

**The answer changes with load.** At LOW load nothing queues: all four
strategies reach CRITICAL requests within an hour, and they differ only in
travel (31–32 km for the distance-aware two, 54 km for the others) and in
throughput (95–96 % against 92 %). The strategies separate at MEDIUM load in the
sparse setting, where the ordering rule starts to decide who waits, and at HIGH
load the differences are an order of magnitude: 76 h of CRITICAL waiting under
FIFO, 0.7 h under the multi-objective strategy, on the same requests.

**And with density.** With eighteen providers the queue never forms at LOW or
MEDIUM load, waiting time is zero for every strategy, and geo-nearest and
multi-objective become the same policy (identical numbers: with one pending
request the ranking is moot and both take the nearest free provider). What
remains is a fairness cost of efficiency: nearest-first dispatch concentrates
work on the providers who live near the demand (utilisation Gini 0.39 at
DENSE/LOW against 0.08 for the idle-longest rule, in all ten datasets), while
the distance-blind strategies spread the work almost evenly by sending whoever
has waited longest — 48 km further on average. At DENSE/HIGH a queue appears
and the ordering matters again (FIFO 2.2 h CRITICAL wait, the others under 0.3
h), but the differences are hours, not days.

**Regional fairness moved little.** Jain's index over 24-hour coverage stays at
1.00 wherever there is no backlog and falls only under HIGH/SPARSE, most for
geo-nearest (0.94: the under-provisioned central settlement is served locally
and slowly) and least for the multi-objective strategy (0.99), whose regional
term is doing what it was designed to do. The index is insensitive when every
region is served equally badly (FIFO at HIGH/SPARSE scores 0.96 with 5 % of
urgent requests reached in time), which is why it is a secondary metric here.

**What this means for the platform.** The multi-objective strategy is the right
default for a *ranked queue that humans work through*: it keeps critical
requests at the top and its distance term stops the queue from sending
responders across the region. It is not the right rule for *automatic
assignment under overload*, where the study says throughput — and therefore
distance — dominates and the honest choice is a distance-aware rule with an
explicit urgency override rather than a score that always wins on urgency. The
weighted score on its own — a priority-ranked queue with no distance-aware
provider choice — is the one combination the data argue against: it inherits
the travel cost of FIFO and adds the starvation of prioritisation.

## 5. Threats to validity

- **Synthetic data.** Arrivals are uniform over the horizon; real demand
  clusters after events and by time of day. Urgency, vulnerability flags and
  household size are drawn independently of each other and of location, which
  real populations do not do. The urgency mix is the plan's assumption, not an
  observed distribution.
- **No real travel times.** Distance is straight-line and speed is a constant
  40 km/h; roads, traffic, and the difference between a city street and a
  regional road are absent. Providers return home after every job; in practice
  they chain jobs. Both simplifications shrink the absolute distance effect but
  do not change its direction.
- **No provider drop-out, no capacity limits, no resources.** Every provider
  is always available, never declines a request, and can serve any request —
  the production resource-eligibility filter (`ProviderResourceService`) and
  organizations are not modelled. Real provision is patchier, which would widen
  the gaps between strategies rather than close them.
- **No time-of-day effects.** Neither arrivals nor provider availability follow
  a daily cycle; a 72-hour horizon with a night would queue differently.
- **Handling time** is uniform 1–3 h and independent of the request; a family
  of ten does not take longer than a single person.
- **Ranking and provider selection are confounded** in two of the four
  strategies: FIFO and WEIGHTED_SCORING are evaluated with the interface's
  default provider choice (first free, ordered by idle time), GEO_NEAREST and
  MULTI_OBJECTIVE_OPTIMIZATION with the nearest free provider. A 2 × 2 design
  (ordering rule × selection rule) would separate the two effects; the study
  keeps the plan's four strategies as they are defined in code, and the
  discussion attributes effects to the rule that plausibly caused them rather
  than to the strategy as a whole.
- **One geometry.** Three settlements, one demand split, providers spread
  evenly. The regional metric in particular depends on that choice; with
  providers placed in proportion to demand the regional differences would
  shrink.
- **Windows and indices.** "Urgent within 6 h", "coverage within 24 h" and the
  72-hour horizon are choices; Jain's index is blind to regions that are
  uniformly badly served (§4). The Gini is over busy hours, so a provider who
  travels far scores as "utilised" while delivering less.
- **The drain.** Serving the queue to the end after the last arrival gives
  complete waiting-time distributions, but a real system under such overload
  would see cancellations and re-filed requests, which shorten the measured
  tail.
- **Sample size.** Ten repetitions per cell are enough for the large effects
  reported here (every headline contrast holds in 10 of 10 or 9 of 10 paired
  datasets); the MEDIUM/SPARSE comparison of weighted scoring and geo-nearest
  on CRITICAL waiting (8 of 10) is the one that could move with more seeds.
- **No production traffic.** The platform has no operational history to
  validate the model against; `FUTURE_WORK.md` (Evaluation) records the replay
  of real requests and the 2 × 2 design as the next steps.

## 6. Reproducing the study

```bash
./mvnw -q -DskipTests package
java -cp target/platform-1.0.0.jar \
     -Dloader.main=com.humanitarian.platform.evaluation.MatchingStudyMain \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     --out docs/evaluation/ev-1 --reps 10 --seed 20260917
```

The run takes under a minute, needs no database and no Spring context, prints
one line per dataset and ends with `checksum 5e46688a92554258`. Every file in
`ev-1/` is regenerated byte for byte; `git status` stays clean after a rerun. A
different `--seed` gives a different set of datasets and, within the standard
deviations reported, the same conclusions; `--reps` changes the number of
repetitions per cell. `MatchingStudyTest` runs a reduced design twice in the
build and asserts that the two agree to the last digit.

Code: `com.humanitarian.platform.evaluation` (`DatasetSpec`,
`SyntheticDataGenerator`, `SyntheticDataset`, `SimulationClock`,
`MatchingSimulation`, `RunMetrics`, `MatchingStudy`, `StudyReport`,
`SvgCharts`, `MatchingStudyMain`), `service.matching.GeoNearestStrategy`, and
the `Clock` constructor of `service.PriorityScoreService`. Tests:
`SyntheticDataGeneratorTest`, `MatchingSimulationTest`, `MatchingStudyTest`,
`GeoNearestStrategyTest`, `PriorityScoreServiceTest.agingFollowsTheInjectedClock`.
