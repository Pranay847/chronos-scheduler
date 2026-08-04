# ADR 0006 — Deriving the idempotency key from the scheduled time

**Status:** Accepted · **Date:** 2026-08

## Context

[ADR 0002](0002-at-least-once-delivery.md) commits to at-least-once delivery with a key the receiver
deduplicates on. That only works if every delivery of one logical firing carries the *same* key —
and the correctness of the whole delivery model reduces to which field that key is derived from.

This ADR exists because that choice is genuinely subtle and gets made wrong by default.

## Decision

```
job_<jobId>_run_<currentRunScheduledFor-epochMillis>
```

with a dedicated field, `currentRunScheduledFor`, separate from `nextRunAt`.

## The trap

The natural implementation derives the key from `nextRunAt` — it is the field that means "when this
runs", it is already on the document, and it holds the correct value on the first attempt.

Then the retry path does its job:

```java
.set("nextRunAt", now.plus(backoff))   // schedules the retry
```

and attempt 2 has a different key than attempt 1. The receiver — following the deduplication
instructions in our own README — processes the same logical event once per attempt.

**Nothing fails.** No exception, no log line, no failed test. Every happy-path test passes because
they never retry. The bug is only observable from the receiver's side, as duplicated side effects,
and by then it has been in production for as long as retries have.

## The rule the two fields encode

| Field | Question it answers | Retry | Cron rollover | Replay |
|---|---|---|---|---|
| `nextRunAt` | when to next *poll* | moves | moves | — |
| `currentRunScheduledFor` | which *firing* this is | **stays** | moves | new |

- A **retry** is another attempt at the same firing → same key, so the receiver collapses them.
- A **cron occurrence** is a genuinely new event → new key. Sharing keys across occurrences would be
  worse than useless: a deduplicating receiver would drop every run of a daily job after the first,
  forever.
- A **replay** gets a new key, which is the least obvious call here. It is an operator saying "send
  this now", usually after fixing what broke. Reusing the original key would let a receiver that
  *had* processed the original silently drop it — a button that reports success and does nothing,
  which is the worst possible behaviour for a control whose entire purpose is "make this happen."
  The cost is that replaying an already-processed job delivers it twice; that is visible, explicitly
  chosen, and something at-least-once already asks receivers to tolerate.

## How it is defended

Three assertions, because this cannot be caught by reading the code:

- `everyRetryOfOneFiringCarriesTheSameIdempotencyKey` — 503, 503, 200; all three requests must reach
  the receiver with one key.
- `aRetryMovesNextRunAtButNotTheScheduledTime` — asserted on the document, so a refactor that folds
  the two fields together fails loudly.
- The chaos test asserts **zero keys map to more than one scheduled time** across 4,000 jobs and two
  killed workers.

## Consequences

One extra field per job, and a rule that has to be remembered at three write sites. In exchange, the
duplicates that at-least-once delivery inevitably produces are duplicates a receiver can actually
absorb — which is the difference between the delivery model working and merely being described in a
README.
