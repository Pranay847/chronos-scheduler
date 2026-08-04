# ADR 0005 — Virtual threads for webhook dispatch

**Status:** Accepted · **Date:** 2026-08

## Context

Delivering a webhook is almost entirely waiting on somebody else's server. A worker may hold
hundreds of deliveries open at once, each doing nothing but blocking on I/O.

## Decision

```java
Executors.newVirtualThreadPerTaskExecutor()
```

with the blocking `java.net.http.HttpClient`, on Java 21.

## Why

A platform-thread pool sized for this workload is mostly idle threads holding a megabyte of stack
each, and its size becomes a hard ceiling on concurrent deliveries. Virtual threads move that
ceiling from thread count to memory, so a small container holds thousands of in-flight requests.

The alternative — reactive/async I/O — achieves the same throughput but rewrites the dispatch path
into callbacks or a reactive chain. Virtual threads get it with ordinary blocking code that reads
top to bottom, which matters more in a component whose ordering is the subtle part
([ADR 0003](0003-lease-based-locking.md)).

## What virtual threads do *not* remove

They remove the usual reason to cap concurrency, which makes it easy to miss that a different reason
still applies: **a claimed job's lease is already running.** Claiming work faster than it can be
delivered does not make it happen sooner — it makes jobs queue while their leases drain, and past a
point the reaper starts reclaiming jobs whose delivery has not even begun.

So `PollerService` enforces `chronos.max-in-flight` regardless. The limit is the lease, not the
threads.

## Consequences

Pinning is not a concern here: `java.net.http` is virtual-thread friendly and the dispatch path
holds no `synchronized` block across I/O. Worth re-checking if a blocking library with internal
`synchronized` sections ever enters that path.

Java 21 is the floor, which is a deliberate pin — the toolchain is fixed at 21 so the local JDK, CI
and the container image agree.
