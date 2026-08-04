# ADR 0001 — Coordinate through MongoDB `findAndModify` rather than a message queue

**Status:** Accepted · **Date:** 2026-08 · **Supersedes:** —

## Context

Workers need to agree on who runs each job, with no two workers running the same one. The obvious
tool is a queue — SQS, RabbitMQ, Kafka — all of which solve exactly this.

## Decision

Coordinate entirely through atomic `findAndModify` on the `jobs` collection. No external queue, no
lock service, no leader election.

```java
Query query = new Query(Criteria.where("status").is(PENDING).and("nextRunAt").lte(now))
        .with(Sort.by(ASC, "nextRunAt"));
Update update = new Update().set("status", CLAIMED).set("lockedBy", workerId)
        .set("lockExpiresAt", now.plus(lease)).inc("claimCount", 1);
mongoTemplate.findAndModify(query, update, options().returnNew(true), Job.class);
```

`findAndModify` is atomic at the document level. Two workers racing for the same document: one wins
the write, the other's filter no longer matches, and it moves to the next candidate.

## Why not a queue

**Because these are not messages.** A queue moves immutable events from producer to consumer. A
scheduled job is *mutable state you keep querying*: paused, resumed, rescheduled, cancelled, its
retry count inspected, its history listed. Modelling that on a queue means a queue *plus* a database
holding the real state, and then a consistency problem between them — a job cancelled in the
database whose message is already in flight.

**Because the scheduling requirement doesn't fit.** SQS caps delayed delivery at 15 minutes. A job
scheduled for next March needs a datastore either way, at which point the queue is a second system
for something the first already does.

**Honest counterpoint:** for a pure queue workload — take work off, do it, never look at it again —
SQS is a better fit than this, and would be the right answer. The distinguishing question is whether
you ever need to *query or modify* pending work. Here you do, constantly.

## Consequences

**Good.** One system to run, back up and reason about. Scaling is adding containers — no partition
rebalancing, no consumer-group coordination. Claim, state and history are one document, so there is
no cross-system consistency problem to have.

**Bad.** Claim throughput is bounded by MongoDB write throughput; measured at 82 majority-acked
inserts/sec on a single-node set ([BENCHMARKS.md §3](../../BENCHMARKS.md)). A queue would go further.
Every worker polls, so there is a constant floor of database queries whether or not there is work.

**The bit that surprised us.** The atomic claim stops two workers *starting* the same job. It does
nothing about two workers *finishing* it — a slow worker whose lease expired mid-delivery will
happily write its result over the worker that legitimately owns the job now. That needed a second
mechanism ([ADR 0003](0003-lease-based-locking.md)), and it is the failure a queue would have given
us for free via visibility timeouts plus explicit ack.
