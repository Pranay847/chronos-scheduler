#!/usr/bin/env bash
#
# Chaos test, Kubernetes edition.
#
#   ./k8s/chaos-k8s.sh [JOB_COUNT] [WINDOW_SECONDS] [KILL_COUNT] [MODE]
#
#   MODE = kill (default) | evict
#
# The compose version (load/chaos.sh) kills containers and restarts them itself. This one deletes
# pods and lets the ReplicaSet controller notice and reschedule. The application-level assertions
# are identical -- the difference is who performs the recovery, and that difference is the point:
# the same lease logic now runs under an orchestrator that decides when workers die.
#
# ---------------------------------------------------------------------------------------------
# MODE matters more than it looks, and conflating the two proves the wrong thing.
#
#   evict  ->  kubectl delete pod <name>
#              Kubernetes sends SIGTERM and waits terminationGracePeriodSeconds. That runs the
#              application's @PreDestroy: the worker stops claiming, drains in-flight deliveries,
#              and hands back leases it never started. This is a ROLLING DEPLOY, not a crash.
#              Expected duplicates: ~zero. If you kill pods this way and report "survived worker
#              death", you have tested the graceful path and called it a crash.
#
#   kill   ->  kubectl delete pod <name> --grace-period=0 --force
#              No SIGTERM, no drain, no @PreDestroy. The kubelet removes the container immediately
#              and the API server drops the pod record. This is the node-died case: leases are
#              still held by a worker that no longer exists, and only expiry plus the reaper can
#              recover them. THIS is the crash-recovery test. Duplicates are expected here and
#              must be bounded by what was in flight.
#
# Run both. The contrast is the evidence: identical no-loss result, materially different duplicate
# counts, and the difference is entirely attributable to whether @PreDestroy got to run.
# ---------------------------------------------------------------------------------------------

set -uo pipefail

JOBS=${1:-4000}
WINDOW=${2:-40}
KILL=${3:-2}
MODE=${4:-kill}

NS=chronos
MONGO_POD=chronos-mongo-0
OUT=build/chaos-k8s

say() { printf '\n\033[1m=== %s\033[0m\n' "$*"; }
# `--eval` below is the mongosh CLI flag for running a script, not a language-level eval.
mongo_run() { kubectl exec -n "$NS" "$MONGO_POD" -- mongosh --quiet --eval "$1" 2>/dev/null | tr -d '\r'; }
sink_pod() { kubectl get pod -n "$NS" -l app=chronos-sink -o jsonpath='{.items[0].metadata.name}'; }

REPLICAS=$(kubectl get deploy -n "$NS" chronos-worker -o jsonpath='{.spec.replicas}')
say "Chaos run on Kubernetes: $JOBS jobs over ${WINDOW}s, $REPLICAS workers, ${MODE}ing $KILL mid-run"

say "1. Scaling workers to 0 and clearing state"
# Same ordering rule as the compose version, for the same reason: workers left running would drain
# the previous run's jobs into the receiver journal AFTER the reset, and the run would report more
# distinct keys than jobs seeded -- which reads as corruption and is really a dirty measurement.
kubectl scale -n "$NS" deploy/chronos-worker --replicas=0 >/dev/null
kubectl wait -n "$NS" --for=delete pod -l app=chronos-worker --timeout=120s >/dev/null 2>&1
mongo_run 'const d=db.getSiblingDB("chronos"); d.jobs.deleteMany({}); d.job_executions.deleteMany({}); d.dead_letters.deleteMany({}); print("cleared");'

# Verify rather than assume. mongo_run swallows stderr, so a transient exec failure here would
# leave the previous run's data in place and the script would carry on happily -- seeding 4000 on
# top of 4000 and then reporting the OLD run's anomalies as if they were new. That happened: a run
# reported `early: 2` that turned out to be two records from twenty minutes earlier, and the only
# reason it was caught is that the job count printed 8000 when 4000 were seeded. An assertion is
# cheaper than noticing.
LEFTOVER=$(mongo_run 'const d=db.getSiblingDB("chronos"); print(d.jobs.countDocuments({}) + d.job_executions.countDocuments({}) + d.dead_letters.countDocuments({}));')
if [ "${LEFTOVER:-unknown}" != "0" ]; then
  echo "FATAL: state was not cleared (jobs+executions+dead_letters = ${LEFTOVER:-unreadable})."
  echo "       Every downstream number would mix this run with the previous one. Aborting."
  exit 1
fi
echo "   verified: collections empty before seeding"

say "2. Restoring $REPLICAS workers, then resetting the receiver journal"
kubectl scale -n "$NS" deploy/chronos-worker --replicas="$REPLICAS" >/dev/null
kubectl rollout status -n "$NS" deploy/chronos-worker --timeout=300s
sleep 5   # let anything still in flight land, so the reset is genuinely final
kubectl exec -n "$NS" "$(sink_pod)" -- curl -sS -X DELETE "http://localhost:8080/__admin/requests" >/dev/null 2>&1
echo "   receiver journal reset"
kubectl get pods -n "$NS" -l app=chronos-worker -o wide

say "3. Seeding $JOBS jobs due over the next ${WINDOW}s"
mongo_run "
const db = db.getSiblingDB('chronos');
const now = Date.now(), TOTAL = $JOBS, WINDOW = $WINDOW * 1000, BATCH = 5000;
for (let off = 0; off < TOTAL; off += BATCH) {
  const docs = [];
  for (let i = off; i < Math.min(off + BATCH, TOTAL); i++) {
    const at = new Date(now + 15000 + Math.floor(WINDOW * i / TOTAL));
    docs.push({_class:'dev.pranay.chronos.domain.Job', tenantId:'default', name:'chaos-'+i,
      schedule:{type:'ONE_TIME',runAt:at,cronExpression:null,timezone:'UTC',misfirePolicy:'FIRE_ONCE'},
      target:{url:'http://chronos-sink:8080/hook/'+i,method:'POST',headers:{},payload:{n:i},timeoutMs:5000},
      retryPolicy:{maxAttempts:5,backoffBaseMs:500,backoffMaxMs:10000},
      status:'PENDING', nextRunAt:at, currentRunScheduledFor:at,
      attempt:0, claimCount:0, version:NumberLong('0'), lockedBy:null, lockExpiresAt:null,
      lastRunAt:null, lastStatusCode:null, lastError:null, triggeredFrom:null,
      completedAt:null, createdAt:new Date(), updatedAt:new Date()});
  }
  db.jobs.insertMany(docs, {ordered:false});
}
print('seeded ' + db.jobs.countDocuments({}));
" | grep seeded

mkdir -p "$OUT"

# Measure the node's wall clock WHILE the run happens, because "nothing fired early" is only a
# meaningful assertion on a clock that moves forwards.
#
# It does not, here. On this kind cluster the clock steps BACKWARDS by 43-60ms every 30 seconds --
# WSL2 periodically resyncs the VM clock to the Windows host, and the VM runs slightly fast. A
# scheduler decides "is this job due" by comparing wall clock against a wall-clock target, so a
# backwards step makes a job that was legitimately due appear to have fired before its own
# scheduled time. Chronos claims with `nextRunAt <= now` and reads `startedAt` afterwards from the
# same JVM, so negative drift is otherwise structurally impossible.
#
# So the tolerance is measured rather than guessed: a negative drift within the largest observed
# backwards step is the clock, and a negative drift beyond it is a bug. Asserting a flat zero here
# would be asserting that WSL2 does not do the thing it demonstrably does every thirty seconds.
CLOCK_PROBE_SECONDS=$(( WINDOW + 90 ))
kubectl exec -n "$NS" "$MONGO_POD" -- bash -c "
  prev=\$(date +%s%N); worst=0
  end=\$(( \$(date +%s) + $CLOCK_PROBE_SECONDS ))
  while [ \$(date +%s) -lt \$end ]; do
    cur=\$(date +%s%N); d=\$(( (cur - prev) / 1000000 ))
    if [ \$d -lt \$worst ]; then worst=\$d; echo \"step \${d}ms at \$(date -u +%H:%M:%S.%3N)\"; fi
    prev=\$cur
  done
  echo \"WORST_BACKWARDS_STEP_MS=\$worst\"
" > "$OUT/clock-probe.txt" 2>/dev/null &
CLOCK_PROBE_PID=$!

KILL_AT=$(( WINDOW / 3 ))
say "4. Running for ${KILL_AT}s, then removing $KILL pod(s) with mode=$MODE"
sleep "$KILL_AT"
VICTIMS=$(kubectl get pods -n "$NS" -l app=chronos-worker -o jsonpath='{.items[*].metadata.name}' | tr ' ' '\n' | head -n "$KILL")
HELD=$(mongo_run "db.getSiblingDB('chronos').jobs.countDocuments({status:'CLAIMED'})")
echo "   jobs CLAIMED across the fleet at kill time: $HELD"

for v in $VICTIMS; do
  if [ "$MODE" = "kill" ]; then
    echo "   SIGKILL (no grace period): $v"
    kubectl delete pod -n "$NS" "$v" --grace-period=0 --force >/dev/null 2>&1
  else
    echo "   SIGTERM (graceful drain): $v"
    kubectl delete pod -n "$NS" "$v" >/dev/null 2>&1
  fi
done

# The orchestration claim, captured as evidence rather than asserted in prose: nobody ran a
# restart command. The ReplicaSet controller observed the delta and acted on it.
say "5. Kubernetes rescheduling (no manual intervention)"
kubectl get pods -n "$NS" -l app=chronos-worker -o wide | tee "$OUT/pods-after-kill.txt"
kubectl rollout status -n "$NS" deploy/chronos-worker --timeout=300s
echo
kubectl get pods -n "$NS" -l app=chronos-worker -o wide | tee "$OUT/pods-recovered.txt"
kubectl get events -n "$NS" --sort-by=.lastTimestamp 2>/dev/null | tail -25 | tee "$OUT/events.txt"

say "6. Draining"
DEADLINE=$(( $(date +%s) + WINDOW + 300 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  REMAINING=$(mongo_run "db.getSiblingDB('chronos').jobs.countDocuments({status:{\$in:['PENDING','CLAIMED']}})")
  [ "$REMAINING" = "0" ] && break
  printf '\r   %s jobs still outstanding...' "$REMAINING"
  sleep 5
done
echo

say "7. Assertions"
# Collect the clock measurement before asserting on drift.
wait "$CLOCK_PROBE_PID" 2>/dev/null
CLOCK_STEP=$(grep -o 'WORST_BACKWARDS_STEP_MS=-\?[0-9]*' "$OUT/clock-probe.txt" 2>/dev/null | cut -d= -f2)
CLOCK_STEP=${CLOCK_STEP:-0}
echo "   worst backwards clock step observed on the node during this run: ${CLOCK_STEP}ms"

kubectl exec -n "$NS" "$(sink_pod)" -- curl -sS "http://localhost:8080/__admin/requests?limit=200000" > "$OUT/requests.json"
mongo_run "
const db = db.getSiblingDB('chronos');
print(JSON.stringify({
  executions: db.job_executions.countDocuments({}),
  jobs: db.jobs.countDocuments({}),
  succeeded: db.jobs.countDocuments({status: 'SUCCEEDED'}),
  failed: db.jobs.countDocuments({status: 'FAILED'}),
  outstanding: db.jobs.countDocuments({status: {\$in: ['PENDING','CLAIMED']}}),
  deadLetters: db.dead_letters.countDocuments({}),
  early: db.job_executions.countDocuments({driftMs: {\$lt: 0}}),
  minDrift: (db.job_executions.aggregate([{\$group:{_id:null,m:{\$min:'\$driftMs'}}}]).toArray()[0] || {m:0}).m
}));
" | tail -1 > "$OUT/mongo-stats.json"

python - "$JOBS" "$OUT" "$MODE" "$CLOCK_STEP" <<'PYEOF'
import json, sys, collections

expected, out, mode = int(sys.argv[1]), sys.argv[2], sys.argv[3]
clock_step = int(sys.argv[4] or 0)   # negative, or 0 if the probe found nothing

# The receiver's journal is the authority. Our own execution records are written AFTER a delivery
# returns, so a worker killed in between leaves a delivery with no record -- they undercount by
# construction, in precisely the direction that would flatter a no-loss claim.
with open(f'{out}/requests.json', encoding='utf-8') as fh:
    journal = json.load(fh)

keys = []
for entry in journal.get('requests', []):
    headers = {k.lower(): v for k, v in entry['request'].get('headers', {}).items()}
    key = headers.get('x-idempotency-key')
    if key:
        keys.append(key)

counts = collections.Counter(keys)
distinct = len(counts)
duplicated = {k: c for k, c in counts.items() if c > 1}
over_budget = {k: c for k, c in counts.items() if c > 5}

with open(f'{out}/mongo-stats.json', encoding='utf-8') as fh:
    stats = json.loads(fh.read().strip())

fails = []
def check(ok, label, detail=''):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{(' - ' + detail) if detail else ''}")
    if not ok:
        fails.append(label)

print(f"\n  mode={mode}  seeded={expected}  distinct keys delivered={distinct}  "
      f"total deliveries={len(keys)}  duplicated keys={len(duplicated)}")
print(f"  mongo: {stats}\n")

check(distinct == expected, "no loss", f"{distinct}/{expected} distinct idempotency keys arrived")
check(stats['outstanding'] == 0, "nothing left outstanding", f"{stats['outstanding']} still PENDING/CLAIMED")

# Early firing, judged against the clock this actually ran on rather than an idealised one.
#
# A job can only appear to fire early if the wall clock moved backwards between the claim
# (`nextRunAt <= now`) and the dispatch (`startedAt = now`), both read from the same JVM. On this
# node the clock does exactly that, on a 30-second cycle, so the honest question is not "was there
# any negative drift" but "was any negative drift LARGER than the clock could account for".
min_drift = stats.get('minDrift', 0) or 0
if stats['early'] == 0:
    check(True, "nothing fired early", "no negative drift at all")
elif min_drift >= clock_step:      # both negative; within the measured step
    check(True, "nothing fired early beyond clock error",
          f"{stats['early']} of {expected} within the {clock_step}ms backwards step measured "
          f"on this node (worst drift {min_drift}ms) - attributable to the clock, not the scheduler")
else:
    check(False, "nothing fired early",
          f"worst drift {min_drift}ms EXCEEDS the {clock_step}ms clock step - not explainable by "
          f"the clock, investigate the claim boundary")
check(not over_budget, "retry budget respected", f"{len(over_budget)} keys exceeded 5 attempts")
check(len(duplicated) <= max(50, expected // 100),
      "duplicates bounded", f"{len(duplicated)} keys delivered more than once")

if mode == 'evict':
    # Graceful shutdown hands leases back before the process exits, so a rolling deploy should
    # produce essentially no duplicates. A large number here means @PreDestroy is not running --
    # most likely terminationGracePeriodSeconds is shorter than the app's drain budget.
    check(len(duplicated) <= 5, "graceful drain produced ~no duplicates",
          f"{len(duplicated)} duplicates on a SIGTERM path")

print()
if fails:
    print(f"  FAILED: {', '.join(fails)}")
    sys.exit(1)
print("  All assertions passed.")
PYEOF
RESULT=$?

say "8. Artifacts"
echo "   $OUT/{requests.json,mongo-stats.json,pods-after-kill.txt,pods-recovered.txt,events.txt}"
exit $RESULT
