# Architecture Decision Records

One page per decision: what was chosen, what was rejected, and what it costs. Written at the time,
not reconstructed afterwards — several of these record mistakes that were made and corrected, which
is the part worth reading.

| # | Decision | The short version |
|---|---|---|
| [0001](0001-mongodb-findandmodify-not-a-queue.md) | MongoDB `findAndModify`, not a queue | Jobs are mutable scheduled state you query, not a stream of immutable events. For a pure queue workload SQS would be the better answer. |
| [0002](0002-at-least-once-delivery.md) | At-least-once delivery | Exactly-once is impossible over an unreliable network. Retry, and attach an identity the receiver can deduplicate on. |
| [0003](0003-lease-based-locking.md) | Lease-based locking + lease-verified write-back | The atomic claim stops two workers *starting* a job. A second mechanism is needed to stop two workers *finishing* one. |
| [0004](0004-polling-with-change-stream-wakeup.md) | Polling baseline, change streams on top | Measured: 95% drift reduction at a 2s poll interval, roughly a wash at 200ms. |
| [0005](0005-virtual-threads-for-dispatch.md) | Virtual threads for delivery | Textbook I/O-wait workload. They remove the usual reason to cap concurrency but not the lease-based one. |
| [0006](0006-idempotency-key-derivation.md) | Key derived from the scheduled time | The single subtlest decision here. Deriving it from the obvious field silently breaks every retry. |
| [0007](0007-worker-clock-for-lease-expiry.md) | Worker clock, for now | Skew costs efficiency, not correctness — because duplicates are already handled. Documented with a trigger for revisiting. |
