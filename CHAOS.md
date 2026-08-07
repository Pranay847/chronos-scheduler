# Chaos test

Kill workers mid-run and prove nothing is lost.

Reproduce with `./load/chaos.sh [JOBS] [WINDOW_SECONDS] [WORKERS] [KILL_COUNT]`. The output below is
real, from the parameters shown.

```
=== Chaos run: 4000 jobs over 40s, 5 workers, killing 2 mid-run

=== 3. Seeding 4000 jobs due over the next 40s
seeded 4000

=== 4. Letting it run for 13s, then SIGKILLing 2 worker(s)
   killing chronos-scheduler-worker-1 (jobs currently CLAIMED across fleet: 9)
   killing chronos-scheduler-worker-2 (jobs currently CLAIMED across fleet: 5)

=== 5. Waiting 10s, then restoring the fleet to 5

=== 6. Draining
   1693 jobs still outstanding...   1082 jobs still outstanding...   714 jobs still outstanding...

=== 7. Assertions

  Seeded jobs                : 4000
  Deliveries received        : 4002
  Distinct idempotency keys  : 4000
  Keys delivered >1 time     : 2
  Execution records written  : 3998
  Jobs SUCCEEDED / FAILED    : 4000 / 0
  Still outstanding          : 0

  [PASS] No loss: every seeded job reached the receiver at least once  — 4000/4000 delivered
  [PASS] Backlog fully drained
  [PASS] Retry budget respected: nothing delivered more than maxAttempts times  — 0 keys exceeded 5 deliveries
  [PASS] Nothing fired before its scheduled time  — 0 executions had negative drift
  [PASS] Every duplicate carries one stable identity  — 0 keys mapped to >1 scheduled time

  Duplicate rate: 2/4000 = 0.05%

ALL ASSERTIONS PASSED
```

`docker kill` sends SIGKILL, so the graceful shutdown path is deliberately bypassed. Those two
workers had no chance to hand anything back — the recovery below is entirely lease expiry plus the
reaper.

## Reading the numbers

**4,000 distinct keys for 4,000 jobs: no loss.** Two workers were destroyed while holding 14 claims
between them, and every one of those jobs was delivered by a survivor.

**4,002 deliveries: exactly two duplicates.** This is the number worth understanding, because it is
the system being *correct*, not the system leaking.

A worker can be killed in the window after it has sent a request but before it has recorded the
result. From the database's point of view that job is simply claimed by a worker that stopped
responding, so the lease expires, the reaper returns it to the pool, and someone else delivers it
again. There is no way to avoid this over an unreliable network: you cannot distinguish "the
receiver processed it and the acknowledgement was lost" from "the receiver never got it," and either
choice is wrong some of the time.

Two out of 4,000 is 0.05%, and it is bounded by how many jobs were mid-flight at the instant of the
kill rather than by anything that accumulates.

**3,998 execution records against 4,002 deliveries.** The four-record shortfall is the same window
seen from the other side, and it is why the assertions above are made against the *receiver's*
journal rather than our own audit trail. Execution records are written after delivery, so a worker
killed in between leaves a delivery with no record — our records undercount by construction, which
is precisely the wrong direction for a no-loss claim to lean on.

**Zero keys mapped to more than one scheduled time.** This is the assertion that makes the
duplicates harmless. Both redeliveries carried the *same* `X-Idempotency-Key` as their original,
because the key derives from `currentRunScheduledFor` — which a retry never modifies. A receiver
following the five-line verification in the README deduplicates them away and sees 4,000 events.

If the key had been derived from `nextRunAt` — the obvious field, which the retry path moves — every
one of those duplicates would have arrived wearing a fresh identity, indistinguishable from a
genuinely new event. Nothing else in this run would have looked any different.

**Zero fired early.** Cheap to assert and invisible otherwise: no other check here would notice a
clock error or a negative backoff, because the counts would all still balance.

## What this does and does not prove

It proves the system tolerates losing 40% of its fleet mid-run without losing work, and that the
duplicates it does produce are the bounded, deduplicable kind that at-least-once delivery promises.

It does not prove exactly-once, which is impossible over an unreliable network. The honest claim is
the one the assertions make: **at-least-once, with a stable identity attached to every delivery so
the receiver can make it exactly-once on their side.**

It also runs against a single-node replica set on one machine. A multi-node deployment adds failure
modes this cannot exercise — primary elections in particular, which is why job state is written with
`w:majority` (see `BENCHMARKS.md` for what that costs).

---

## Same test, run by Kubernetes instead of by this script

Everything above kills containers and restarts them from the script. [`k8s/`](k8s/) runs the same
4,000-job test on a real cluster, where pods are deleted and the ReplicaSet controller does the
recovery — nothing in the recovery path is the harness.

Two results there are worth reading even if you never touch the manifests:

- **SIGKILL vs SIGTERM produce 5 duplicates vs 0.** `kubectl delete pod` sends SIGTERM and runs the
  graceful drain, so it tests the rolling-deploy path, not a crash. The crash case needs
  `--grace-period=0 --force`. Reporting the first as "survived worker death" tests the wrong thing.
- **Under SIGKILL, Chronos wrote 3,998 execution records for 4,005 actual deliveries.** Seven
  deliveries happened that the system has no record of. It is the clearest possible argument for
  asserting against the receiver's journal rather than our own bookkeeping — the same reasoning
  this document opens with, demonstrated numerically.

There is also a measured finding about non-monotonic clocks, and two bugs in the harness itself.
See [`k8s/README.md`](k8s/README.md).
