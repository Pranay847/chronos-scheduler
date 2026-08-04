# ADR 0002 — At-least-once delivery, with a stable idempotency key

**Status:** Accepted · **Date:** 2026-08

## Context

A webhook is sent over an unreliable network. If the connection drops after the request leaves but
before the response arrives, we cannot tell "the receiver processed it and the acknowledgement was
lost" from "the receiver never got it."

## Decision

**At-least-once.** Retry on uncertainty, and attach a stable `X-Idempotency-Key` so the receiver can
deduplicate.

## Why exactly-once is not on the table

It is not a matter of effort. In the ambiguous case there are exactly two options and both are wrong
some of the time:

- **Don't retry** → at-most-once. Real deliveries are silently lost whenever an acknowledgement is.
- **Retry** → at-least-once. Some deliveries arrive twice.

There is no third option, because the information needed to choose correctly does not exist on our
side of the connection. What *is* achievable is exactly-once *processing*, which requires the
receiver to deduplicate — so the useful thing we can build is the identity that makes that possible.

This is what Stripe, GitHub and Shopify do, for the same reason.

## The key must be stable across retries — and this is the trap

```
job_<jobId>_run_<currentRunScheduledFor-epochMillis>
```

Derived from `currentRunScheduledFor`: *the scheduled time of the firing*. Not from `nextRunAt`.

`nextRunAt` is the obvious field and it holds the right value on the first attempt, so a key derived
from it looks correct in every happy-path test. But the retry path sets `nextRunAt = now + backoff`,
so attempt 2 gets a different key than attempt 1 — and the receiver, doing exactly what our README
told it to, processes the same logical event once per attempt.

The two fields exist as a pair precisely to keep this straight:

| | Moves on retry? | Moves on cron rollover? |
|---|---|---|
| `nextRunAt` — when to next *poll* | yes | yes |
| `currentRunScheduledFor` — which *firing* this is | **no** | yes |

A retry is the same firing again, so its identity is preserved. A cron occurrence is a genuinely new
event, so it gets a new one — sharing keys across occurrences would be worse than useless, since a
deduplicating receiver would drop every run of a daily job after the first, forever.

## Consequences

Receivers must deduplicate to get exactly-once processing, and the README ships a five-line verifier
so that is a small ask. Duplicates are bounded rather than eliminated: measured at **0.05%** (2 in
4,000) while two of five workers were SIGKILLed mid-run ([CHAOS.md](../../CHAOS.md)).

The chaos test asserts that every duplicate carries one stable key. Without that assertion the
duplicates would be indistinguishable from distinct events, and nothing else in the run would look
any different.
