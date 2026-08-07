# Chronos on Kubernetes

The crash-recovery story in [`CHAOS.md`](../CHAOS.md) was demonstrated by a bash script that killed
containers and restarted them itself. Here the orchestrator does it: pods are deleted, the ReplicaSet
controller notices and reschedules, and the application's lease recovery runs underneath. Same
assertions, but nothing in the recovery path is the test harness.

That distinction is the reason this directory exists. "I wrote crash-recovery logic" and "I ran it
under something that kills my processes on its own schedule" are different claims.

---

## Measured

kind v0.30.0, Kubernetes v1.34.0, single node, 3 worker replicas, MongoDB StatefulSet.
4,000 jobs seeded across a 40-second window, 2 of 3 pods removed a third of the way in.

| | `kill` (SIGKILL, `--grace-period=0 --force`) | `evict` (SIGTERM, plain `delete pod`) |
|---|---|---|
| Distinct keys delivered | **4000 / 4000** | **4000 / 4000** |
| Total deliveries | 4005 | 4000 |
| **Duplicate keys** | **5** | **0** |
| Execution records written | 3998 | 4000 |
| Left outstanding | 0 | 0 |
| Retry budget exceeded | 0 | 0 |
| Recovery performed by | ReplicaSet controller | ReplicaSet controller |

**The duplicate column is the entire argument.** Same cluster, same command, same job count — the
only difference is whether `@PreDestroy` got to run. Five duplicates versus zero is graceful
shutdown doing measurable work, and it is why calling `kubectl delete pod` a crash test is wrong.

**The execution-record column is the second finding.** Under SIGKILL, Chronos wrote 3,998 records
for 4,005 actual deliveries. Seven deliveries genuinely happened that the system has no record of,
because pods died between the HTTP call returning and the record being written. Had the assertions
counted our own records instead of the receiver's journal, this run would have reported data loss
that did not occur. That gap only appears under `--grace-period=0`; it is zero on the graceful path.

### The clock is not monotonic, and the test measures it

An early run failed `nothing fired early` — 2 of 4000 delivered ~55ms before their scheduled time.
That should be structurally impossible: the claim filters `nextRunAt <= now` and `startedAt` is read
afterwards from the same JVM, so drift cannot go negative unless the wall clock moves backwards.

It does. Probing the node's clock:

```
BACKWARDS STEP: -60ms at 04:10:16.673
BACKWARDS STEP: -43ms at 04:10:46.633
BACKWARDS STEP: -56ms at 04:11:16.582
samples=25690 backwards_jumps=3
```

Every 30 seconds, 43–81ms backwards — WSL2 resyncing a VM clock that runs fast. Every observation
fits: the early drifts (−53, −59, −7ms) all sit inside the step range, and 1–2 per run matches 1–2
steps per 40-second window.

So the assertion now **measures the node's worst backwards step during the run** and judges against
it: negative drift within the measured step is the clock, beyond it is a bug. On a machine with a
stable clock the tolerance is zero and the check is as strict as it ever was. Asserting a flat zero
would have meant asserting that WSL2 does not do the thing it does twice a minute.

This generalises past WSL2. NTP steps happen on real servers, which is why `ntpd` slews rather than
jumps by default — a scheduler that compares wall clock against wall-clock targets is exposed to it
by construction, and no monotonic clock fixes it, because "is 03:58:17.583 in the past" is a
wall-clock question. The real mitigation is sourcing the due-check from the database's clock so
every worker shares one time source.

### Autoscaling on backlog, not CPU

```
TIME   DEPTH    HPA READING   REPLICAS
0s     0        0             2
26s    17376    17376500m     2      <- backlog registered
38s    17376    17376500m     6      <- scaled
~2m    3470     3470500m      10     <- at maxReplicas, draining
```

40,000 overdue jobs seeded against 2 replicas; the HPA scaled to 10 on queue depth alone. Reproduce
with `./k8s/hpa-demo.sh`. There is no metrics-server in this cluster — dropping the CPU metric
removed that dependency, since the only metric is external and served by prometheus-adapter.

**Two ways to get this wrong, both silent.** Every worker exports the *fleet-wide* depth tagged with
its own worker id; it is not a per-pod value that happens to carry a pod label.

- `sum()` in the adapter reports `depth × replicas`. Adding a worker to drain a backlog makes the
  metric go **up**, which adds workers, which makes it go up again — a feedback loop that pins the
  deployment at `maxReplicas` while every dashboard shows more queued jobs than exist. Use `max()`.
- `type: Pods` in the HPA averages across pods before dividing. Since every pod reports the same
  number, the average *is* the depth, giving `currentReplicas × depth / target` — over-scaling by a
  factor of the current replica count, compounding on each scale-up. Use `type: External` with
  `AverageValue`, which gives `ceil(depth / target)`.

Neither produces an error. Both produce confident, wrong autoscaling.

### Debugging note: the discovery endpoint is not the contract

`kubectl get --raw /apis/external.metrics.k8s.io/v1beta1` returns `NotFound` even when everything
works — external metrics are unbounded and resolved on demand rather than enumerated. Time was lost
concluding from that endpoint that discovery had failed while the HPA could have answered the
question immediately:

```bash
kubectl describe hpa -n chronos chronos-worker    # ScalingActive / ValidMetricFound is the truth
```

One real bug did surface on the way: `max(...)` without `by (<<.GroupBy>>)` collapses the
`namespace` label, leaving the adapter nothing to key the result on.

### Two harness bugs, both found by numbers not adding up

Worth recording because both produced *confident wrong conclusions* rather than obvious failures.

1. **The state clear failed silently.** `mongo_run` swallows stderr, so a transient failure left the
   previous run's data in place and the next run seeded 4,000 jobs on top of 4,000. The early-firing
   records from the earlier run were then re-reported as a fresh reproduction. The only tell was
   `jobs: 8000` against 4,000 seeded. The script now asserts the collections are empty before
   seeding and aborts loudly otherwise.
2. **A vacuous piece of evidence.** While investigating the clock, a query sorted executions *by*
   `startedAt` and concluded from the ordered output that `startedAt` was monotonic — which sorting
   guarantees regardless. It proved nothing and briefly argued against the correct explanation.

---

## Run it

Needs a cluster (Docker Desktop's built-in Kubernetes, kind, or minikube) and the image built
locally. Nothing is pulled from a registry — `imagePullPolicy: IfNotPresent` with a `:local` tag.

```bash
docker build -t chronos-scheduler:local .
# kind only — Docker Desktop shares its daemon with the cluster, so this is unnecessary there:
# kind load docker-image chronos-scheduler:local

kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/10-mongo.yaml
kubectl apply -f k8s/11-mongo-init.yaml      # initiates rs0; safe to re-apply
kubectl wait -n chronos --for=condition=complete job/chronos-mongo-init --timeout=180s
kubectl apply -f k8s/40-sink.yaml
kubectl apply -f k8s/20-worker.yaml
kubectl apply -f k8s/30-scaling.yaml         # PDB always; HPA needs metrics-server

kubectl rollout status -n chronos deploy/chronos-worker
kubectl get pods -n chronos -o wide
```

Reach the API:

```bash
kubectl port-forward -n chronos svc/chronos-worker 8080:8080
curl localhost:8080/actuator/health
```

Tear down with `kubectl delete namespace chronos`. Note that this deletes the PVC and therefore the
job data.

---

## The three decisions worth defending

### 1. One image, N identical replicas — not a split API/worker tier

Every replica runs the REST API, the poller, the dispatcher and the reaper in one JVM. Coordination
is a single atomic `findAndModify` against MongoDB; there is no leader and no per-instance
configuration.

Splitting into an "API Deployment" and a "worker Deployment" would look more sophisticated and would
be worse. It invents a role distinction the design does not have, and it undermines the actual claim,
which is that scaling is `replicas: N` and nothing else. The interesting property is that the pods
are interchangeable — obscuring that to add a box to a diagram is a bad trade.

### 2. Three probes, not one — and liveness must not touch the database

This is the decision most likely to be got wrong, and the failure mode is severe.

| Probe | Endpoint | Includes Mongo? | Answers |
|---|---|---|---|
| `startupProbe` | `/actuator/health/liveness` | no | "still booting, don't judge me yet" |
| `livenessProbe` | `/actuator/health/liveness` | **no** | "restart me" |
| `readinessProbe` | `/actuator/health/readiness` | **yes** | "send me traffic" |

**Why Mongo must not be in liveness.** If it were, a database blip fails the probe on every replica
simultaneously. Kubernetes kills the entire fleet. The replacements start, fail the identical probe
against the identical unavailable database, and get killed too — a restart storm produced by the
health check rather than by the fault, at exactly the moment the system is already degraded.

This is not hypothetical. The Render deployment of this project sat failing a Mongo-dependent health
check for fifteen minutes while the application itself was serving requests fine. The platform gave
up on a working process. That is what a liveness probe with a dependency in it does.

**Why Mongo belongs in readiness.** A worker that cannot reach the database cannot serve an API
request, so removing it from the Service is correct. Crucially this does *not* stop the poller — an
unready worker keeps trying to claim and rejoins on its own when Mongo returns. No restart, no lost
leases.

**Why a startupProbe.** This app takes roughly 70 seconds to boot on constrained CPU. Without a
startup probe, liveness starts counting immediately, fails during a perfectly normal startup, and
kills the pod before it finishes booting. The resulting crash loop looks like an application bug and
is entirely self-inflicted. While the startup probe runs, the other two are suspended.

[`HealthProbesTest`](../src/test/java/dev/pranay/chronos/HealthProbesTest.java) pins the invariant:
the liveness group contains no external dependency.

### 3. Memory is limited, CPU is not

A CPU limit is enforced by CFS throttling: once a container exceeds its quota, runnable threads are
parked until the next 100ms period. This project's headline metric is p99 scheduling drift in the
low hundreds of milliseconds. A throttling stall lands directly in that number — the measurement
would be reporting the cgroup config rather than the scheduler.

`requests.cpu` is still set, so the scheduler places pods sensibly. Memory *is* limited, because the
failure mode there is different: an unbounded JVM gets OOM-killed by the kernel with no
`OutOfMemoryError` and no stack trace — the logs simply stop.

---

## The chaos test, and the distinction that makes it mean something

```bash
./k8s/chaos-k8s.sh 4000 40 2 kill     # SIGKILL — the crash case
./k8s/chaos-k8s.sh 4000 40 2 evict    # SIGTERM — the rolling-deploy case
```

**`kubectl delete pod` is not a crash.** It sends SIGTERM and waits
`terminationGracePeriodSeconds`, which runs the application's `@PreDestroy`: the worker stops
claiming, drains in-flight deliveries, and hands back leases it never started. That is a rolling
deploy. Reporting it as "survived worker death" tests the graceful path and calls it a crash.

**The crash case needs `--grace-period=0 --force`.** No SIGTERM, no drain. The kubelet removes the
container immediately and the API server drops the pod record, so leases are still held by a worker
that no longer exists. Only lease expiry plus the reaper can recover them. This is the node-died
case, and it is the one worth claiming.

Both should show zero loss. They should differ sharply in duplicates: near-zero for `evict`,
non-zero but bounded for `kill`. That gap is the evidence that graceful shutdown is doing real work
— and if `evict` produces many duplicates, `terminationGracePeriodSeconds` is probably shorter than
the app's drain budget.

The assertions come from the **receiver's** journal, not from Chronos's own execution records. Those
records are written after a delivery returns, so a worker killed in between leaves a delivery with no
record — they undercount by construction, in exactly the direction that would flatter a no-loss
claim.

---

## Honest limits

- **Single-node MongoDB.** `w:majority` on a one-member replica set is majority-of-one, which is a
  much weaker guarantee than a real three-member set. The write concern is genuinely configured and
  genuinely enforced; the durability it buys here is not production durability.
- **The HPA scales on queue depth, but depth is a lagging signal.** `scheduler_jobs_due_depth`
  counts jobs that are *already* late — by the time it rises, drift has happened. It is the right
  thing to scale on because it is unambiguous, not because it is early. Combined with the control
  loop's own latency (10s gauge refresh + 5s scrape + 15s HPA sync ≈ 20–30s before a backlog is
  visible, plus 40–70s of pod startup), this reacts to load lasting minutes and is blind to a
  ten-second spike.
- **Adding workers does not scale the database.** Every replica claims against one MongoDB replica
  set with a single atomic `findAndModify`. Past some worker count, more replicas mean more
  contention on the same document range rather than more throughput. `maxReplicas` is a real limit
  whose right value depends on hardware not measured here.
- **The PDB only covers voluntary disruption.** Drains and upgrades respect it. A SIGKILLed pod, an
  OOM kill, or a dead node is not an eviction and the PDB has no say — that case is the application's
  lease recovery, which is what the chaos test exercises. Complementary, not redundant.
- **No NetworkPolicy, no resource quotas, no PSS enforcement, no TLS between pods.** A real cluster
  would want all four.
- **`allow-private-targets=true` and `require-api-key=false`** in the ConfigMap, both demo-only and
  both marked as such. Every pod IP is RFC 1918 private, which is precisely what the SSRF guard
  blocks — so with the guard at its production setting, in-cluster deliveries are correctly refused.

---

## Why the StatefulSet, specifically

MongoDB runs as a StatefulSet rather than a Deployment because a replica set member records its own
address at `rs.initiate()` time, and that address has to keep resolving.

This project has a scar that makes the point. On Fly.io the database ran as a plain machine,
`rs.initiate()` recorded the member under the container's own hostname, and nothing outside that
container could resolve it — the driver discovered a member at an address it could never reach and
timed out forever. See [`docs/FLY_DEPLOY.md`](../docs/FLY_DEPLOY.md).

A StatefulSet plus a headless Service fixes that structurally: `chronos-mongo-0` keeps its name and
its DNS record across rescheduling, so the address written into the replica set config on day one is
still correct after the pod is deleted, moved, or restarted. A Deployment's random pod suffix would
invalidate it on the first restart. [`11-mongo-init.yaml`](11-mongo-init.yaml) writes that full DNS
name in explicitly rather than letting `rs.initiate()` guess.
