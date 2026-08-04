# Benchmarks

Every number here came out of the running system — Prometheus, `explain("executionStats")`, or k6 —
rather than from a stopwatch in a test. All of them are reproducible with the commands shown.

Hardware: single machine, Docker Desktop on Windows, MongoDB 7 single-node replica set. These are
relative numbers, useful for comparing designs against each other; absolute throughput on real
infrastructure would differ.

---

## 1. Scheduling drift — 600 jobs over 60s, 3 workers

| Measure | Value |
|---|---|
| Drift p50 | 106 ms |
| Drift p95 | 207 ms |
| **Drift p99** | **236 ms** |
| Jobs delivered | 600 / 600 |
| Leases reclaimed | 0 |
| Leases lost mid-delivery | 0 |

Computed fleet-wide, with `histogram_quantile` over buckets summed across all three workers:

```promql
histogram_quantile(0.99, sum(rate(scheduler_drift_seconds_bucket[5m])) by (le))
```

**Why these are the right numbers rather than just good ones.** The poll interval is 200 ms, and
polling at interval `T` gives mean drift `T/2` and worst case `T`. Predicted ~100 ms and ~200 ms;
measured 106 ms and 236 ms. The design is behaving as analysed.

That agreement is the useful part. A p99 far *below* `T` would mean the load was too light to be
measuring anything, and far above would mean the poller is falling behind and the real bottleneck is
somewhere else.

### Work distribution across the fleet

| Worker | Deliveries |
|---|---|
| `17d9efa40a37-99c0` | 198 |
| `5561c63aedb0-151e` | 197 |
| `6078bf82e74d-a312` | 205 |

Even to within 4%, with **no coordination between workers** — no leader, no partitioning, no work
assignment. That split is the atomic `findAndModify` claim, and it is the property that lets the
fleet scale by adding containers and changing no configuration.

---

## 2. The claim index — 200,000 jobs

The query every worker runs several times a second:

```javascript
db.jobs.find({ status: "PENDING", nextRunAt: { $lte: now } }).sort({ nextRunAt: 1 }).limit(1)
```

Collection shaped like a scheduler that has been running a while: **190,000 completed jobs with older
`nextRunAt` values, 10,000 pending and due**. That ordering is the entire point — see below.

Median of 300 executions per strategy:

| # | Index | Per query | Docs examined | Keys examined | Index size |
|---|---|---|---|---|---|
| **A** | **`{status: 1, nextRunAt: 1}`** — equality before range | **1.223 ms** | **1** | **1** | 2,136 KB |
| B | `{nextRunAt: 1}` — naive single-field | 161.207 ms | 190,001 | 190,001 | 2,132 KB |
| C | `{nextRunAt: 1, status: 1}` — reversed order | 160.327 ms | 1 | 190,001 | 4,496 KB |
| **D** | **A, but partial on `status: "PENDING"`** | **0.913 ms** | **1** | **1** | **116 KB** |

**A vs B: 161.2 ms → 1.2 ms, a 99.2% reduction (132× faster).**

### Why the comparison is against B and not against no index at all

Benchmarking an index against a collection scan proves only that indexes exist. Every candidate here
is a *reasonable* index that a competent person might choose, which is what makes the difference
attributable to field ordering rather than to effort.

With `{nextRunAt: 1}`, Mongo walks the index from the earliest scheduled time forward, checking
`status` on each document. Every one of the 190,000 completed jobs sorts before the first claimable
one, so all of them are examined and discarded. With `status` first, the index seeks straight into
the PENDING region and the first key it touches is the answer.

This is also why the collection has to be shaped realistically to measure anything. On a collection
that is uniformly PENDING, every index above performs identically and the benchmark proves nothing —
the first attempt at this measurement did exactly that and had to be thrown away.

### Row C is the one worth staring at

The reversed index examines **1 document** — which looks excellent, and is the number usually quoted
— while examining **190,001 index keys**, and takes just as long as the naive index.

It covers the filter, so Mongo never fetches the documents it rejects. It still walks the entire
index to find them. `totalDocsExamined` alone would have declared this index fine.
`totalKeysExamined` is where the cost is, and reporting only the first is how a slow query gets
signed off as optimised.

### The partial index is 18× smaller at the same speed

116 KB against 2,136 KB, because it indexes only the ~5% of documents that are claimable. In a
mature collection where nearly everything is terminal, most of a conventional claim index consists
of entries for jobs that will never be claimed again.

The tradeoff is that any query not matching the partial filter cannot use it — acceptable here,
since the claim query is the only thing with this shape.

Reproduce:

```bash
docker exec chronos-mongo mongosh --quiet --eval 'db.getSiblingDB("chronos").jobs.find({status:"PENDING",nextRunAt:{$lte:new Date()}}).sort({nextRunAt:1}).limit(1).explain("executionStats")'
```

---

## 3. Job creation throughput — k6, ramping arrival rate

```bash
docker run --rm --network chronos-scheduler_default -v "$PWD/load:/scripts" grafana/k6 run /scripts/create-jobs.js
```

Open-loop (arrival rate), not closed-loop (fixed VUs). Fixed VUs wait for each response before
sending again, so when the service slows the offered load slows with it — you measure a comfortable
equilibrium and call it capacity.

### The first ceiling was our own rate limiter

| Measure | Value |
|---|---|
| Requests | 27,953 |
| Succeeded | 10,664 (38.1%) |
| Rejected with 429 | 17,289 (61.9%) |
| p95 latency | 48.6 ms |

Latency stayed healthy throughout — this was policy, not saturation. The default tenant allows 1,000
job creations per minute *per worker*, and the offered load was ~24,000/min. The limiter did exactly
what it exists for.

Recorded rather than quietly tuned away, for two reasons: it is the honest first answer to "what is
your bottleneck", and it demonstrates the per-worker caveat of not centralising the limit.

### With the limit raised

| Measure | Value |
|---|---|
| Requests | 27,851 |
| Failures | **0** |
| Sustained rate | **398 req/s ≈ 23,900 jobs/min** |
| Latency p50 / p90 / p95 | 25.1 / 49.1 / 72.5 ms |
| Latency max | 347 ms |
| Dropped iterations at the 1,000/s stage | 149 |

The dropped iterations are the ceiling showing itself: at a 1,000/s target the service could not
accept work as fast as it was offered. **The limit sits between 400 and 1,000 creations per second.**

### The bottleneck, measured

Serial `insertOne` against the same collection, 2,000 inserts each:

| Write concern | Per insert | Inserts/sec |
|---|---|---|
| `w: majority` | 12.255 ms | 82 |
| `w: 1` | 0.699 ms | 1,432 |

**17.5×.** Job creation is bottlenecked on the durability guarantee — not the index, not the API
layer, not the JVM.

That is a deliberate trade rather than an oversight. `w: majority` is what stops an acknowledged
claim from vanishing in a primary election and being handed to a second worker — the failure that
would silently break the one property this project exists to demonstrate. It is also why
`job_executions` uses `w: 1` on its own template: an audit trail can afford to lose its tail, and it
is the highest-volume write in the system.

If creation throughput had to go higher, the honest options are batching creates, or accepting
`w: 1` on creation while keeping `majority` on the *claim* — the write where losing an
acknowledgement actually changes behaviour.

---

## 4. Change-stream wakeup — what the optional feature actually buys

Same workload twice, one variable: `./load/drift-compare.sh 300 <pollIntervalMs>`. 300 jobs each due
3 seconds after creation, 3 workers.

### At a 2s poll interval

| | Polling only | With change stream | Change |
|---|---|---|---|
| min | 838 ms | 9 ms | |
| p50 | 1,142 ms | 42 ms | **−96%** |
| p95 | 1,615 ms | 135 ms | −92% |
| p99 | 1,626 ms | 254 ms | **−84%** |
| **mean** | **1,227 ms** | **55 ms** | **−95.5%** |

296 of 300 jobs were claimed by a wakeup rather than by a poll tick
(`scheduler_wakeup_early_total`).

### At the production 200 ms poll interval

| | Polling only | With change stream |
|---|---|---|
| p50 | 47 ms | 40 ms |
| p95 | 152 ms | 92 ms |
| p99 | 206 ms | 232 ms |
| mean | 63 ms | 47 ms |

### The conclusion, which is a judgement rather than a number

**The change stream's value is inversely proportional to how aggressive the poll interval already
is.** At 2 seconds it removes 95% of the drift. At 200 ms the median and p95 improve modestly, the
p99 is *worse* — the wakeups add contention at the tail without much room left to win.

So the real decision it enables is not "lower drift" but **"low drift without a hot poll loop"**: 2s
polling plus change streams delivers a mean of 55 ms while querying the database a tenth as often as
200 ms polling does. Whether that trade is worth a persistent oplog connection per worker depends
entirely on how many workers there are and what the database costs.

For this deployment, at 200 ms, it is close to a wash — which is why Phase 9 was correctly marked
optional and first on the cut list.

### The measurement mistake that nearly went in this document

The first version of this comparison reported a **60% improvement at a 2s poll interval**. It was
entirely false.

The wakeup horizon was originally one poll interval, so with jobs seeded 3 seconds out and a 2-second
interval, **no timer was ever armed** — both runs were plain polling, and the "improvement" was
run-to-run variance. The number was plausible, in the right direction, and completely fabricated.

What caught it was `scheduler.wakeup.early` reading **zero** while jobs kept firing normally. That
counter exists precisely because a latency optimisation that silently stops working has no other
symptom: nothing errors, nothing fails, the jobs still fire. Without it, a made-up 60% would have
gone into this file and onto a resume.

Two lessons worth keeping: **an optimisation needs a metric that proves it ran**, and **a benchmark
that cannot fail is not measuring anything** — the same mistake as the first index benchmark in §2,
where a uniformly-PENDING collection made every index look identical.

---

## 5. Chaos

Full output and analysis in [CHAOS.md](CHAOS.md). Summary: 4,000 jobs, 5 workers, 2 SIGKILLed
mid-run — **zero loss**, 2 duplicates (0.05%), every duplicate carrying a stable idempotency key.

---

## Reproducing all of it

```bash
docker compose up -d --build --scale worker=3
```

Dashboard at <http://localhost:3000>, Prometheus at <http://localhost:9090>. Then:

```bash
./load/chaos.sh 4000 40 5 2
```

The dashboard screenshot in `docs/` is rendered server-side, so it can be regenerated rather than
re-cropped:

```bash
curl "http://localhost:3000/render/d/chronos-health/chronos-scheduler-health?width=1600&height=1500&theme=dark&kiosk=true" -o docs/grafana-dashboard.png
```
