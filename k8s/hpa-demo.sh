#!/usr/bin/env bash
#
# Drives the HorizontalPodAutoscaler with a real backlog and records what it did.
#
#   ./k8s/hpa-demo.sh [JOB_COUNT]
#
# Seeds a large number of ALREADY-OVERDUE jobs, so `scheduler_jobs_due_depth` jumps immediately,
# then samples the HPA and the replica count until the queue drains.
#
# The backlog has to be big for an honest reason rather than for show. The control loop has real
# latency: the gauge refreshes every 10s inside the app, Prometheus scrapes every 5s, and the HPA
# syncs every 15s -- so roughly 20-30s before a backlog is even visible to the autoscaler, plus
# another ~40-70s for a new worker to boot. A backlog that drains in fifteen seconds is gone before
# any of that completes, and the demo would show a flat replica count while "proving" autoscaling
# works. Sizing it to outlive the loop is the difference between a demonstration and a screenshot.
set -uo pipefail

JOBS=${1:-40000}
NS=chronos
MONGO_POD=chronos-mongo-0
OUT=build/hpa
mkdir -p "$OUT"

say() { printf '\n\033[1m=== %s\033[0m\n' "$*"; }
# `--eval` is the mongosh CLI flag, not a language-level eval.
mongo_run() { kubectl exec -n "$NS" "$MONGO_POD" -- mongosh --quiet --eval "$1" 2>/dev/null | tr -d '\r'; }

say "1. Clearing state and returning to minReplicas"
kubectl scale -n "$NS" deploy/chronos-worker --replicas=2 >/dev/null
mongo_run 'const d=db.getSiblingDB("chronos"); d.jobs.deleteMany({}); d.job_executions.deleteMany({}); print("cleared");'
LEFTOVER=$(mongo_run 'print(db.getSiblingDB("chronos").jobs.countDocuments({}));')
if [ "${LEFTOVER:-x}" != "0" ]; then echo "FATAL: state not cleared ($LEFTOVER left)"; exit 1; fi
kubectl rollout status -n "$NS" deploy/chronos-worker --timeout=300s >/dev/null 2>&1
echo "   starting from $(kubectl get deploy -n $NS chronos-worker -o jsonpath='{.status.readyReplicas}') replicas, 0 jobs"

say "2. Seeding $JOBS jobs, all already overdue"
mongo_run "
const db = db.getSiblingDB('chronos');
const now = Date.now(), TOTAL = $JOBS, BATCH = 5000;
for (let off = 0; off < TOTAL; off += BATCH) {
  const docs = [];
  for (let i = off; i < Math.min(off + BATCH, TOTAL); i++) {
    // Deliberately in the past: the point is a standing backlog, not scheduling precision.
    const at = new Date(now - 5000);
    docs.push({_class:'dev.pranay.chronos.domain.Job', tenantId:'default', name:'hpa-'+i,
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

say "3. Watching the autoscaler"
printf '%-9s %-12s %-10s %-9s %s\n' TIME DUE_DEPTH HPA_TARGET REPLICAS READY | tee "$OUT/trace.txt"
START=$(date +%s)
PEAK=0
for i in $(seq 1 60); do
  T=$(( $(date +%s) - START ))
  TARGET=$(kubectl get hpa -n "$NS" chronos-worker -o jsonpath='{.status.currentMetrics[0].external.current.averageValue}' 2>/dev/null)
  # Depth comes from the HPA's own reading rather than a fresh count query.
  #
  # The first version ran `countDocuments` through `kubectl exec` on every iteration and the column
  # printed "?" for the entire run: exec-ing into the database while it is servicing a 40,000-job
  # backlog is exactly when that call is slowest, so it timed out precisely when the number
  # mattered. Reading what the autoscaler actually saw is both cheaper and more honest -- a
  # separately-measured depth could disagree with the value driving the decision.
  #
  # Kubernetes quantities come back in milli-units (17376500m = 17376.5), so strip the suffix.
  DEPTH=$(printf '%s' "${TARGET:-}" | sed 's/m$//')
  [ -n "$DEPTH" ] && DEPTH=$(( DEPTH / 1000 )) || DEPTH="?"
  DESIRED=$(kubectl get deploy -n "$NS" chronos-worker -o jsonpath='{.spec.replicas}' 2>/dev/null)
  READY=$(kubectl get deploy -n "$NS" chronos-worker -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
  [ "${DESIRED:-0}" -gt "$PEAK" ] 2>/dev/null && PEAK=$DESIRED
  printf '%-9s %-12s %-10s %-9s %s\n' "${T}s" "${DEPTH:-?}" "${TARGET:-<none>}" "${DESIRED:-?}" "${READY:-0}" | tee -a "$OUT/trace.txt"
  # Stop once the backlog is gone AND the fleet has had a moment to settle.
  if [ "${DEPTH:-1}" = "0" ] && [ "$T" -gt 60 ]; then break; fi
  sleep 10
done

say "4. Result"
echo "   peak replicas reached: $PEAK (started at 2, max allowed 10)"
kubectl describe hpa -n "$NS" chronos-worker 2>&1 | sed -n '/Conditions:/,$p' | head -20 | tee "$OUT/hpa-describe.txt"

if [ "$PEAK" -le 2 ]; then
  echo
  echo "   NOTE: replicas never rose above the minimum. Either the backlog drained faster than the"
  echo "   ~30s control-loop latency, or the metric is not reaching the HPA. Check:"
  echo "     kubectl describe hpa -n $NS chronos-worker"
  exit 1
fi
echo "   artifacts: $OUT/trace.txt, $OUT/hpa-describe.txt"
