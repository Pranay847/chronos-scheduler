# ADR 0003 — Lease-based locking, with lease-verified write-back

**Status:** Accepted · **Date:** 2026-08

## Context

A worker that dies holding a claim must not strand that job forever. Something has to decide the
worker is gone and hand the work back.

## Decision

Store `lockedBy` and `lockExpiresAt` on the job document. A reaper sweeps every 10s and returns
expired claims to `PENDING`. **And every terminal write is conditional on still holding the lease.**

No ZooKeeper, no etcd, no distributed lock service, no heartbeats.

## Why a lease and not a failure detector

A worker that died holding a claim is *indistinguishable* from one that is merely slow. There is no
way to tell them apart from outside, and no point building a heartbeat protocol that pretends
otherwise — heartbeats fail under exactly the conditions that make this hard (GC pause, network
partition, an overloaded host), and a failure detector tuned to avoid false positives is just a
slower lease.

So the lease *is* the failure detector, and it is honest about what it does: after this deadline we
assume you are gone and give your work to someone else, whether or not that is true.

## The second half, which is not optional

Because the reaper will absolutely take jobs from workers that are still alive, this sequence is
normal rather than exotic:

```
T+0s   worker A claims, lease expires at T+30s
T+30s  A's HTTP call is still in flight (slow endpoint, GC pause, queue backup)
T+30s  reaper resets job → PENDING
T+31s  worker B claims, delivers, writes SUCCEEDED, rolls nextRunAt → tomorrow
T+33s  A returns and writes SUCCEEDED, rolling nextRunAt → the day after
```

The duplicate delivery is what at-least-once already covers. **The corrupted schedule is not** — a
whole firing has silently vanished and nothing logged an error.

So every terminal write re-asserts ownership:

```java
Criteria.where("id").is(jobId).and("lockedBy").is(workerId).and("lockExpiresAt").gt(now)
```

Zero matched documents means the lease was lost: discard the result, increment
`scheduler.lease.lost`, write nothing.

## Sizing the lease

The obvious rule — `lease > 2 × timeout` — is wrong, because it omits the term that dominates under
load. The lease starts at *claim* time; the HTTP call starts at *dispatch* time; in between the job
sits in the dispatcher queue.

```
lease > queueWait(p99) + connectTimeout + requestTimeout + reaperPeriod + clockSkew
```

Only the timeout term is knowable at startup, so `LeaseSanityCheck` enforces `lease ≥ 3 × timeout`
and `scheduler.lease.lost` carries the empirical half: a non-zero rate means real queue wait has
outgrown the lease.

## Consequences

Recovery latency is bounded by lease duration plus reaper interval — measured at exactly that in
Phase 3, where a SIGKILL stranded 33 claims and a survivor reclaimed all of them the moment the
lease expired. Graceful shutdown short-circuits it entirely by releasing claims on SIGTERM, so a
deploy doesn't cost a full lease of dead air.

`claimCount` is tracked separately from `attempt` so a job that crashes every worker it touches gets
quarantined rather than walking the fleet forever — it never fails a *delivery*, so the retry limit
alone would never catch it.
