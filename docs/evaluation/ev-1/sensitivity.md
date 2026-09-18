# EV-1 sensitivity analysis

The same seeded datasets, re-run with the priority weights changed. The weights themselves are defended in `docs/SCORING.md`; this table asks whether the study's conclusions depend on them.

Ranking is over the cell means; **1** is best on that metric (lowest waiting time, highest coverage, highest completion).

## CRITICAL mean waiting time (h)

| Load / density | production | levelled-vulnerability | doubled-urgency | uncapped-waiting | no-vulnerability |
|---|---|---|---|---|---|
| LOW/SPARSE | Multi-objective (then Geo-nearest) | Multi-objective (then Geo-nearest) | Multi-objective (then Geo-nearest) | Multi-objective (then Geo-nearest) | Multi-objective (then Geo-nearest) |
| MEDIUM/SPARSE | Multi-objective (then Weighted) | Multi-objective (then Weighted) | Multi-objective (then Weighted) | Multi-objective (then Weighted) | Multi-objective (then Weighted) |
| HIGH/SPARSE | Multi-objective (then Weighted) | Multi-objective (then Weighted) | Multi-objective (then Weighted) | Multi-objective (then Weighted) | Multi-objective (then Weighted) |

## Urgent requests assigned within 6 h (%)

| Load / density | production | levelled-vulnerability | doubled-urgency | uncapped-waiting | no-vulnerability |
|---|---|---|---|---|---|
| LOW/SPARSE | Weighted (then Geo-nearest) | Geo-nearest (then Multi-objective) | Weighted (then Geo-nearest) | Weighted (then Geo-nearest) | Geo-nearest (then Multi-objective) |
| MEDIUM/SPARSE | Multi-objective (then Geo-nearest) | Multi-objective (then Geo-nearest) | Multi-objective (then Weighted) | Multi-objective (then Geo-nearest) | Multi-objective (then Weighted) |
| HIGH/SPARSE | Multi-objective (then Geo-nearest) | Multi-objective (then Geo-nearest) | Multi-objective (then Weighted) | Multi-objective (then Geo-nearest) | Multi-objective (then Geo-nearest) |

## Completed within the 72 h horizon (%)

| Load / density | production | levelled-vulnerability | doubled-urgency | uncapped-waiting | no-vulnerability |
|---|---|---|---|---|---|
| LOW/SPARSE | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) |
| MEDIUM/SPARSE | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) |
| HIGH/SPARSE | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) |

## p95 waiting time, all requests (h)

| Load / density | production | levelled-vulnerability | doubled-urgency | uncapped-waiting | no-vulnerability |
|---|---|---|---|---|---|
| LOW/SPARSE | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) |
| MEDIUM/SPARSE | Geo-nearest (then FIFO) | Geo-nearest (then FIFO) | Geo-nearest (then FIFO) | Geo-nearest (then FIFO) | Geo-nearest (then FIFO) |
| HIGH/SPARSE | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Geo-nearest (then Multi-objective) | Multi-objective (then Geo-nearest) | Geo-nearest (then Multi-objective) |

## What the production weights' own numbers do

| Cell | Strategy | Metric | production | levelled-vulnerability | doubled-urgency | uncapped-waiting | no-vulnerability |
|---|---|---|---:|---:|---:|---:|---:|
| LOW/SPARSE | FIFO | CRITICAL mean waiting time (h) | 1.07 | 1.07 | 1.07 | 1.07 | 1.07 |
| LOW/SPARSE | FIFO | Urgent requests assigned within 6 h (%) | 97.9 | 97.9 | 97.9 | 97.9 | 97.9 |
| LOW/SPARSE | FIFO | Completed within the 72 h horizon (%) | 92.1 | 92.1 | 92.1 | 92.1 | 92.1 |
| LOW/SPARSE | FIFO | p95 waiting time, all requests (h) | 4.52 | 4.52 | 4.52 | 4.52 | 4.52 |
| LOW/SPARSE | Weighted | CRITICAL mean waiting time (h) | 0.54 | 0.47 | 0.57 | 0.54 | 0.58 |
| LOW/SPARSE | Weighted | Urgent requests assigned within 6 h (%) | 100 | 99.5 | 100 | 100 | 99.6 |
| LOW/SPARSE | Weighted | Completed within the 72 h horizon (%) | 91.4 | 91.7 | 92.2 | 91.4 | 92.4 |
| LOW/SPARSE | Weighted | p95 waiting time, all requests (h) | 6.39 | 5.87 | 6.32 | 6.39 | 5.57 |
| LOW/SPARSE | Geo-nearest | CRITICAL mean waiting time (h) | 0.32 | 0.32 | 0.32 | 0.32 | 0.32 |
| LOW/SPARSE | Geo-nearest | Urgent requests assigned within 6 h (%) | 100 | 100 | 100 | 100 | 100 |
| LOW/SPARSE | Geo-nearest | Completed within the 72 h horizon (%) | 95.6 | 95.6 | 95.6 | 95.6 | 95.6 |
| LOW/SPARSE | Geo-nearest | p95 waiting time, all requests (h) | 1.92 | 1.92 | 1.92 | 1.92 | 1.92 |
| LOW/SPARSE | Multi-objective | CRITICAL mean waiting time (h) | 0.24 | 0.24 | 0.24 | 0.24 | 0.22 |
| LOW/SPARSE | Multi-objective | Urgent requests assigned within 6 h (%) | 100 | 100 | 100 | 100 | 100 |
| LOW/SPARSE | Multi-objective | Completed within the 72 h horizon (%) | 95.1 | 95.1 | 94.7 | 95.1 | 95.4 |
| LOW/SPARSE | Multi-objective | p95 waiting time, all requests (h) | 2.29 | 2.29 | 2.34 | 2.29 | 2.34 |
| MEDIUM/SPARSE | FIFO | CRITICAL mean waiting time (h) | 21.3 | 21.3 | 21.3 | 21.3 | 21.3 |
| MEDIUM/SPARSE | FIFO | Urgent requests assigned within 6 h (%) | 30.9 | 30.9 | 30.9 | 30.9 | 30.9 |
| MEDIUM/SPARSE | FIFO | Completed within the 72 h horizon (%) | 59.2 | 59.2 | 59.2 | 59.2 | 59.2 |
| MEDIUM/SPARSE | FIFO | p95 waiting time, all requests (h) | 42.6 | 42.6 | 42.6 | 42.6 | 42.6 |
| MEDIUM/SPARSE | Weighted | CRITICAL mean waiting time (h) | 2.38 | 2.56 | 1.09 | 2.61 | 1.61 |
| MEDIUM/SPARSE | Weighted | Urgent requests assigned within 6 h (%) | 67.5 | 66.9 | 85.8 | 66.3 | 70.4 |
| MEDIUM/SPARSE | Weighted | Completed within the 72 h horizon (%) | 58.9 | 58.4 | 58.0 | 59.0 | 58.3 |
| MEDIUM/SPARSE | Weighted | p95 waiting time, all requests (h) | 77.9 | 77.3 | 82.7 | 63.6 | 79.3 |
| MEDIUM/SPARSE | Geo-nearest | CRITICAL mean waiting time (h) | 8.37 | 8.37 | 8.37 | 8.37 | 8.37 |
| MEDIUM/SPARSE | Geo-nearest | Urgent requests assigned within 6 h (%) | 69.1 | 69.1 | 69.1 | 69.1 | 69.1 |
| MEDIUM/SPARSE | Geo-nearest | Completed within the 72 h horizon (%) | 79.5 | 79.5 | 79.5 | 79.5 | 79.5 |
| MEDIUM/SPARSE | Geo-nearest | p95 waiting time, all requests (h) | 40.6 | 40.6 | 40.6 | 40.6 | 40.6 |
| MEDIUM/SPARSE | Multi-objective | CRITICAL mean waiting time (h) | 0.96 | 0.83 | 0.87 | 0.94 | 0.89 |
| MEDIUM/SPARSE | Multi-objective | Urgent requests assigned within 6 h (%) | 89.9 | 91.3 | 93.6 | 89.9 | 92.4 |
| MEDIUM/SPARSE | Multi-objective | Completed within the 72 h horizon (%) | 68.1 | 68.9 | 67.3 | 68.1 | 68.9 |
| MEDIUM/SPARSE | Multi-objective | p95 waiting time, all requests (h) | 55.9 | 52.0 | 60.8 | 52.9 | 50.7 |
| HIGH/SPARSE | FIFO | CRITICAL mean waiting time (h) | 80.8 | 80.8 | 80.8 | 80.8 | 80.8 |
| HIGH/SPARSE | FIFO | Urgent requests assigned within 6 h (%) | 19.1 | 19.1 | 19.1 | 19.1 | 19.1 |
| HIGH/SPARSE | FIFO | Completed within the 72 h horizon (%) | 29.8 | 29.8 | 29.8 | 29.8 | 29.8 |
| HIGH/SPARSE | FIFO | p95 waiting time, all requests (h) | 155.0 | 155.0 | 155.0 | 155.0 | 155.0 |
| HIGH/SPARSE | Weighted | CRITICAL mean waiting time (h) | 22.7 | 20.9 | 7.54 | 33.1 | 13.0 |
| HIGH/SPARSE | Weighted | Urgent requests assigned within 6 h (%) | 31.8 | 30.3 | 37.1 | 31.0 | 26.5 |
| HIGH/SPARSE | Weighted | Completed within the 72 h horizon (%) | 29.5 | 29.4 | 29.8 | 29.6 | 29.5 |
| HIGH/SPARSE | Weighted | p95 waiting time, all requests (h) | 188.0 | 187.9 | 192.8 | 163.6 | 190.7 |
| HIGH/SPARSE | Geo-nearest | CRITICAL mean waiting time (h) | 44.9 | 44.9 | 44.9 | 44.9 | 44.9 |
| HIGH/SPARSE | Geo-nearest | Urgent requests assigned within 6 h (%) | 32.1 | 32.1 | 32.1 | 32.1 | 32.1 |
| HIGH/SPARSE | Geo-nearest | Completed within the 72 h horizon (%) | 40.8 | 40.8 | 40.8 | 40.8 | 40.8 |
| HIGH/SPARSE | Geo-nearest | p95 waiting time, all requests (h) | 126.6 | 126.6 | 126.6 | 126.6 | 126.6 |
| HIGH/SPARSE | Multi-objective | CRITICAL mean waiting time (h) | 3.12 | 3.09 | 2.55 | 3.11 | 2.27 |
| HIGH/SPARSE | Multi-objective | Urgent requests assigned within 6 h (%) | 44.4 | 45.6 | 45.4 | 43.7 | 44.1 |
| HIGH/SPARSE | Multi-objective | Completed within the 72 h horizon (%) | 33.7 | 34.3 | 34.0 | 34.1 | 33.6 |
| HIGH/SPARSE | Multi-objective | p95 waiting time, all requests (h) | 136.2 | 134.1 | 135.1 | 116.1 | 134.8 |
