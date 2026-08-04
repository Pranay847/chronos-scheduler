# ADR 0004 — Polling as the baseline, change streams as an optimisation

**Status:** Accepted · **Date:** 2026-08

## Context

Workers need to notice that a job is due. Poll on a timer, or subscribe to MongoDB change streams
and react to inserts.

## Decision

**Poll every 200ms.** Layer a change-stream wakeup on top that arms a one-shot timer at
`nextRunAt` for jobs due within 30 seconds.

Polling is the baseline and is never removed. The change stream only ever makes things faster.

## Why polling is the floor and not the fallback

Polling at interval `T` gives mean drift `T/2` and worst case `T`. At 200ms that is sub-second
precision, which is what a webhook scheduler needs. Measured p50 106ms and p99 236ms against a
predicted 100ms and 200ms — the design behaving as analysed.

Change streams add a persistent connection per worker and a dependency on the oplog. A stream can
die; a resumable stream that falls behind its resume token cannot recover. If the design *depended*
on it, those become outages. Because polling runs underneath, the failure mode is **drift returns to
the polling baseline** rather than jobs stop firing. `ChangeStreamDisabledTest` asserts this by
removing the bean entirely and checking everything still fires.

## What it actually buys — measured, not assumed

300 jobs due 3 seconds after creation, 3 workers, one variable
([BENCHMARKS.md §4](../../BENCHMARKS.md)):

| Poll interval | Polling only (mean / p99) | With change stream |
|---|---|---|
| 2,000 ms | 1,227 / 1,626 ms | **55 / 254 ms** |
| 200 ms | 63 / 206 ms | 47 / 232 ms |

**The benefit is inversely proportional to how aggressive the poll interval already is.** At 2s it
removes 95% of the drift. At 200ms it is close to a wash — the median improves, the p99 is slightly
*worse*, because the wakeups add tail contention with little room left to win.

So the decision this enables is not "lower drift" but **low drift without a hot poll loop**: 2s
polling plus change streams gives a 55ms mean while querying the database a tenth as often.

## Three things that had to be got right

**An insert notification alone is useless.** A job created now to run in two seconds cannot be
claimed now — waking immediately finds nothing. The win comes from a timer *at* `nextRunAt`, which
makes this a timer manager rather than a nudge.

**The wakeups need their own thread pool.** Queued onto the scheduler shared with the poller, a
burst of inserts put hundreds of tasks in front of the very poll cycle they were meant to
accelerate — measured drift roughly **12× worse than plain polling**.

**The horizon must be wider than one poll interval.** That was the first choice, and it silently
excluded the exact case the feature exists for. A benchmark then reported a 60% improvement that was
pure run-to-run variance, because no timer had ever been armed. `scheduler.wakeup.early` reading
zero while jobs kept firing is what caught it — an optimisation that silently stops working has no
other symptom.

## Consequences

Requires a replica set, which the stack has run as since day one. Enabled by default; disabling it
costs latency and nothing else. Given the ~wash at 200ms, running without it is a perfectly
defensible configuration — which is why it is a flag rather than an assumption.
