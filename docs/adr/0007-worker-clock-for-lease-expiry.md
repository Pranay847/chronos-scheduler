# ADR 0007 — Worker clock for lease expiry, for now

**Status:** Accepted, with a known limitation · **Date:** 2026-08

## Context

A lease deadline is written by the worker that claims a job (`now.plus(leaseDuration)`) and
evaluated by whichever worker's reaper happens to sweep (`lockExpiresAt < now`). Those are two
different clocks on two different hosts.

## Decision

Use each worker's own clock, and size the lease with enough headroom to absorb realistic skew.

## The problem this leaves open

If worker B's clock runs 5 seconds ahead of worker A's, B's reaper considers A's leases expired 5
seconds early. Every such reclaim is a duplicate delivery — the job is handed to someone else while
A is still working on it.

Within one Docker host this is nil, since every container shares the host clock. Across EC2
instances with NTP it is typically single-digit milliseconds, but NTP can fail, and a VM resumed from
a snapshot can be badly wrong.

## The alternative, and why it is deferred

MongoDB can stamp the deadline itself using `$$NOW` in an aggregation-pipeline update:

```javascript
[{ $set: { lockExpiresAt: { $add: ["$$NOW", leaseDurationMs] } } }]
```

Then one clock — the database's — governs both writing and evaluating the deadline, and skew stops
being a variable at all. It is the correct answer for a multi-region deployment.

Deferred because it costs the readability of the claim: an aggregation-pipeline update is
meaningfully harder to read than `Update.set()`, and the claim is the piece of code most worth
keeping legible. On a single-region deployment with NTP the skew is orders of magnitude below the
30-second lease.

## What makes it safe to defer

Skew produces *early* reclaims, which produce duplicate deliveries — and duplicates are already
covered:

- [ADR 0002](0002-at-least-once-delivery.md): every delivery carries a stable idempotency key, so a
  receiver absorbs the duplicate.
- [ADR 0003](0003-lease-based-locking.md): the lease-verified write-back means the early-reclaimed
  worker discards its result rather than corrupting state.

So clock skew degrades *efficiency*, not correctness. That is the property that turns this from a
bug into a tunable.

## Trigger for revisiting

`scheduler.lease.lost` rising without a corresponding rise in load. That counter increments whenever
a worker finishes a delivery it no longer owns, which is exactly what early reclaims look like from
the inside — and it is the signal that would distinguish clock skew from a lease that is simply too
short for real queue wait.
