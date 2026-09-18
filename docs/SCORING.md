# The priority model, and why each weight is what it is

*The weights in `PriorityScoreService` decide whose request is looked at first
when there are more requests than providers. They were undefended: the numbers
existed, the reasoning did not. This document supplies it, states what each
weight commits the platform to, and says where the model can be argued with —
because a reviewer will ask why a disability outweighs children, and "it looked
right" is not an answer.*

## 1. What the score is for

A single integer, 0–100, that orders a queue. It is not a measure of suffering
and it does not decide who is helped — everyone in the queue is helped — only
**who is reached first when capacity is short**. Two facts about where it acts
follow from that, and both matter for the evaluation chapter:

- On arrival, a request is matched alone against whoever is free
  (`AutomaticAssignmentService`), so the score orders nothing.
- The score decides order in exactly two places: the **retry sweep** of requests
  nobody took (`StaleRequestScheduler`, GAP-2) and the **ranked queue** humans
  browse (`/api/v1/admin/dashboard/ranked`, `help-requests.html`).

So the model's real effect is on the requests that wait — which is precisely the
population it should be arguing about.

## 2. The weights

| Factor | Points | Where |
|---|---:|---|
| Urgency: CRITICAL / HIGH / MEDIUM / LOW | 40 / 30 / 20 / 10 | `urgencyScore` |
| A person with a disability in the household | +15 | `hasDisabled` |
| Children in the household | +10 | `hasChildren` |
| Elderly people in the household | +10 | `hasElderly` |
| Household size | +2 per person, capped at +20 | `peopleCount` |
| Waiting time | +0.5 per full hour, capped at +20 | `waitingTimeScore` |
| **Total** | **clamped to 100** | `clamp` |

A psychological request scores urgency, `+35` if the crisis detector fired, and
the same waiting-time growth.

## 3. Why

### Urgency is the largest single term (40 points, 40 % of the scale)

Urgency is the only factor that speaks about **time to harm** rather than about
who is affected. A critical request is one where waiting causes damage — no
water today, insulin gone, a night outdoors in winter. Everything else on the
list describes vulnerability, which raises the *cost* of waiting but does not
set a deadline. If any single factor is to dominate, it has to be the one with
the clock attached.

The spacing (40 / 30 / 20 / 10) is deliberately even rather than exponential.
An exponential scale (say 40 / 20 / 10 / 5) would make a single CRITICAL request
outrank any number of HIGH ones, which turns the queue into strict triage by
label and hands the ordering to whoever fills in the form. Even spacing means a
HIGH request with several vulnerability factors can outrank a bare CRITICAL one
— 30 + 15 + 10 + 10 + 2 = 67 against 40 + 2 = 42 — which is the intended behaviour:
a household with a disabled person, children and elderly members that says
"high" should not sit behind a single adult who ticked "critical".

### Disability outweighs children and elderly (15 against 10)

This is the weight a reviewer will challenge, and it is a judgement about
**substitutability of help**, not about whose life is worth more.

- A household with children has, in the ordinary case, an adult who can travel
  to a distribution point, queue, carry, and improvise. Children raise the
  consequence of failure sharply, which is why the factor is there at all.
- A household containing a person with a disability may have no such
  substitute. Mobility, sensory or cognitive impairment can make the informal
  alternatives — walking to a neighbour, standing in a queue, carrying twenty
  litres of water — unavailable at any price. Aid that arrives late is often not
  late but *absent*, because the household could not bridge the gap by itself.
- Elderly households sit between the two: often reduced mobility, often some
  informal support, and the same +10 as children reflects that mixture rather
  than a claim that age and childhood are equivalent.

So the 5-point gap encodes "this household is least able to substitute for the
platform", not "this person matters more". Note also that the factors **add**: a
household with an elderly person who is also disabled scores +25, which is the
intended cumulative reading.

The gap is small on purpose — but not so small that it never bites. Fifteen
points is more than the ten between two urgency bands, so a HIGH request from a
household with a disabled member (30 + 15 + 2 = 47) outranks a fresh CRITICAL
one from a single adult (40 + 2 = 42). That is the model's sharpest edge and it
is intended: an unverified self-reported label should not beat a stated,
checkable household fact. §5 says what to argue with if you disagree.

### Household size is +2 per person, capped at +20

Aid is roughly linear in people: ten people need about ten times one person's
food. But the queue is not about volume, it is about order, and an unbounded
term would let one very large household outrank everything else permanently. The
cap at +20 (ten people) says: beyond ten, the difference in *urgency of
ordering* stops growing, even though the delivery gets bigger. Volume is handled
where it belongs — the capacity check in `ProviderResourceService`, which
compares `peopleCount` against what a provider actually has.

### Waiting time is +0.5 per hour, capped at +20

This is the anti-starvation term, and its two constants answer two different
questions.

- **The rate** (half a point per hour) sets how fast a waiting request climbs:
  20 points in 40 hours, so a LOW request with no other factors (10 + 2 = 12)
  reaches 32 after two days — above a fresh MEDIUM single-person request (22),
  below a fresh CRITICAL one (42). Waiting earns you a place, never the front
  of the line.
- **The cap** (+20) guarantees that no amount of waiting lets a request outrank
  a fresh critical one. This is a deliberate refusal of pure ageing: in a
  humanitarian queue, a two-week-old request for clothing must not be served
  before a child who needs water today. The cost of that choice is that a
  low-priority request can, in a permanently overloaded system, wait for ever —
  which is exactly why GAP-2 exists: after 24 hours with no provider, the
  request is escalated to a human instead of being left to the arithmetic.

Recomputation runs every 30 minutes (`PriorityScoreScheduler`), so the term is
live rather than frozen at submission.

### The clamp at 100

The database has `CHECK (priority_score BETWEEN 0 AND 100)`, so the model must
not exceed it: the worst case — CRITICAL, children, elderly, disabled, ten or
more people, two days' wait — sums to 115 and is clamped. The clamp is not a
rounding detail; it means **the top of the scale is saturated**, and above 100
the model stops discriminating. Requests that reach it are ordered among
themselves by id, i.e. arbitrarily. That is a real limitation (§5).

### The crisis bonus for psychological requests (+35)

A detected crisis is not a "more urgent" request; it is a different kind of
event with a different response (automatic routing to an on-duty psychologist,
`AUTO_CRISIS`). The +35 exists so that a crisis case outranks any non-crisis
case of the same urgency — 40 + 35 = 75 against at most 40 + 20 — rather than to
fine-tune an order. The crisis detector's own thresholds are documented in
`docs/BACKEND_GUIDE.md` (L-2).

## 4. What the model refuses to do

Stating the non-goals is part of defending the weights:

- **No factor for who asked.** A request filed on someone's behalf scores
  exactly like one they filed themselves.
- **No history.** A household that has been helped before is not penalised, and
  a first-time asker gets no bonus. The platform has no view of need outside the
  request in front of it.
- **No geography in the score.** Distance is a matching concern, not a priority
  one: a remote household is not less deserving, it is more expensive to reach,
  and that trade-off belongs to the strategy (EV-1), not to the queue's order.
- **No provider preference.** The score cannot be raised by a provider, only by
  the facts of the request and the clock.

## 5. Where the model can be argued with

- **Self-reported urgency is the largest term and is unverified.** Nothing stops
  a requester from marking everything CRITICAL, and the model has no correction
  for it — deliberately, because a correction would penalise the least
  sophisticated users hardest. The even spacing (§3) limits the damage; a
  verification step or a per-account calibration would be the real answer, and
  neither exists.
- **The saturation at 100 loses information** exactly where it matters most:
  among the most severe requests. A scale that compressed rather than clamped
  (for example a soft maximum) would keep ordering above 100 without breaking
  the CHECK constraint.
- **The vulnerability factors are booleans.** One child and five children score
  the same +10; a mild and a profound disability score the same +15. Finer
  granularity would need a more intrusive form.
- **The three vulnerability weights (15, 10, 10) are the least evidence-based
  numbers in the model.** They encode the substitutability argument of §3, which
  is a reasoned position and not an empirical measurement. Anyone replacing them
  with figures from field practice — or from a protection cluster's
  vulnerability criteria — would be improving the model, not breaking it.
- **The waiting cap makes indefinite waiting possible.** GAP-2's escalation is
  a mitigation, not a fix: it moves the problem to a human, which is the honest
  place for it, but a platform with real load data might prefer an ageing term
  that eventually overtakes urgency.

## 6. Sensitivity

Because the weights are arguable, EV-1 reports how much the comparison depends
on them: the study is re-run with the vulnerability weights levelled, with
urgency doubled, and with the waiting cap removed, and the resulting strategy
ranking is compared with the ranking under the weights above. See the
sensitivity section of
[`docs/evaluation/EV-1-matching-study.md`](evaluation/EV-1-matching-study.md).

## 7. Where this is implemented

`PriorityScoreService` is the single source of truth; the database trigger that
once computed a different score was removed in V15, and no dataset in the
evaluation mixes the two. The model is unit-tested in `PriorityScoreServiceTest`
(including that the worst case clamps to exactly 100) and checked against the
database CHECK in `HelpRequestPersistenceTest`.
