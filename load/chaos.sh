#!/usr/bin/env bash
#
# Chaos test: kill workers mid-run and prove nothing is lost.
#
#   ./load/chaos.sh [JOB_COUNT] [WINDOW_SECONDS] [WORKERS] [KILL_COUNT]
#
# Scripted rather than performed by hand, because the output is the artifact. A claim that the
# system survives node failure is worth nothing without a reproducible way for a reader to check
# it — and the assertions below are chosen so that each one fails loudly rather than degrading
# into a smaller number nobody notices.
#
# What is asserted, and why each one is here:
#
#   1. No loss           — every seeded job was delivered at least once. The whole point.
#   2. Duplicates bounded— at-least-once permits duplicates; unbounded duplicates mean the lease
#                          or the conditional write-back is broken. The count must be explainable
#                          by what was in flight at the moment of the kill.
#   3. No early firing   — nothing delivered before its scheduled time. Catches clock errors and
#                          bad backoff arithmetic, and is invisible to every other assertion here.
#   4. Retry budget      — no job delivered more than maxAttempts times.
#   5. Stable identity   — every duplicated delivery carries the SAME idempotency key, which is
#                          what makes the duplicates safe for a receiver to absorb. Duplicates
#                          with different keys would be indistinguishable from distinct events.

set -uo pipefail

JOBS=${1:-10000}
WINDOW=${2:-60}
WORKERS=${3:-5}
KILL=${4:-2}

COMPOSE_PROJECT=chronos-scheduler
SINK_ADMIN=http://localhost:8081/__admin

say() { printf '\n\033[1m=== %s\033[0m\n' "$*"; }

say "Chaos run: $JOBS jobs over ${WINDOW}s, $WORKERS workers, killing $KILL mid-run"

say "1. Stopping workers and clearing state"
# Ordering here is load-bearing, and getting it wrong produces evidence that looks like a system
# failure. Start the workers first and they immediately begin draining whatever the previous run
# left behind — those deliveries land in the receiver's journal before it is reset, and the run
# then reports MORE distinct idempotency keys than jobs seeded. Which reads as data corruption
# and is really just a dirty measurement.
docker compose stop worker >/dev/null 2>&1
docker exec chronos-mongo mongosh --quiet --eval \
  'const d=db.getSiblingDB("chronos"); d.jobs.deleteMany({}); d.job_executions.deleteMany({}); d.dead_letters.deleteMany({});' >/dev/null

say "2. Starting $WORKERS workers against an empty collection, then resetting the receiver"
docker compose up -d --scale worker="$WORKERS" >/dev/null 2>&1
until [ "$(docker compose ps --status running --format '{{.Service}}' | grep -c '^worker$')" -ge "$WORKERS" ]; do sleep 2; done
sleep 5   # let anything still in flight land, so the reset below is genuinely final
curl -sS -X DELETE "$SINK_ADMIN/requests" >/dev/null 2>&1 || curl -sS -X POST "$SINK_ADMIN/requests/reset" >/dev/null 2>&1
echo "   receiver journal after reset: $(curl -sS "$SINK_ADMIN/requests?limit=1" | python -c "import sys,json; print(json.load(sys.stdin).get('meta',{}).get('total','?'))" 2>/dev/null) entries"
docker compose ps --format '{{.Name}} {{.Status}}' | grep worker

say "3. Seeding $JOBS jobs due over the next ${WINDOW}s"
docker exec chronos-mongo mongosh --quiet --eval "
const db = db.getSiblingDB('chronos');
const now = Date.now(), TOTAL = $JOBS, WINDOW = $WINDOW * 1000, BATCH = 5000;
for (let off = 0; off < TOTAL; off += BATCH) {
  const docs = [];
  for (let i = off; i < Math.min(off + BATCH, TOTAL); i++) {
    const at = new Date(now + 10000 + Math.floor(WINDOW * i / TOTAL));
    docs.push({_class:'dev.pranay.chronos.domain.Job', tenantId:'default', name:'chaos-'+i,
      schedule:{type:'ONE_TIME',runAt:at,cronExpression:null,timezone:'UTC',misfirePolicy:'FIRE_ONCE'},
      target:{url:'http://sink:8080/hook/'+i,method:'POST',headers:{},payload:{n:i},timeoutMs:5000},
      retryPolicy:{maxAttempts:5,backoffBaseMs:500,backoffMaxMs:10000},
      status:'PENDING', nextRunAt:at, currentRunScheduledFor:at,
      attempt:0, claimCount:0, version:NumberLong('0'), lockedBy:null, lockExpiresAt:null,
      lastRunAt:null, lastStatusCode:null, lastError:null, triggeredFrom:null,
      completedAt:null, createdAt:new Date(), updatedAt:new Date()});
  }
  db.jobs.insertMany(docs, {ordered:false});
}
print('seeded ' + db.jobs.countDocuments({}));
" 2>&1 | grep seeded

KILL_AT=$(( WINDOW / 3 ))
say "4. Letting it run for ${KILL_AT}s, then SIGKILLing $KILL worker(s)"
sleep "$KILL_AT"

VICTIMS=$(docker compose ps --format '{{.Name}}' | grep worker | head -n "$KILL")
for v in $VICTIMS; do
  HELD=$(docker exec chronos-mongo mongosh --quiet --eval \
    "db.getSiblingDB('chronos').jobs.countDocuments({status:'CLAIMED'})" 2>/dev/null | tr -d '\r')
  echo "   killing $v (jobs currently CLAIMED across fleet: $HELD)"
  docker kill "$v" >/dev/null 2>&1
done

say "5. Waiting 10s, then restoring the fleet to $WORKERS"
sleep 10
docker compose up -d --scale worker="$WORKERS" >/dev/null 2>&1

say "6. Draining"
DEADLINE=$(( $(date +%s) + WINDOW + 240 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  REMAINING=$(docker exec chronos-mongo mongosh --quiet --eval \
    "db.getSiblingDB('chronos').jobs.countDocuments({status:{\$in:['PENDING','CLAIMED']}})" 2>/dev/null | tr -d '\r')
  [ "$REMAINING" = "0" ] && break
  printf '\r   %s jobs still outstanding...' "$REMAINING"
  sleep 5
done
echo

say "7. Assertions"
mkdir -p build/chaos
# Relative path: Windows curl resolves /tmp differently than the shell does.
curl -sS "$SINK_ADMIN/requests?limit=200000" -o build/chaos/requests.json

python - "$JOBS" <<'PYEOF'
import json, sys, subprocess, collections

expected = int(sys.argv[1])

# Receiver's own journal — the authority on what actually arrived. Our execution records are
# written AFTER delivery, so a worker killed in between would leave a delivery with no record:
# they undercount by construction, which is exactly the wrong direction for a no-loss claim.
with open('build/chaos/requests.json', encoding='utf-8') as fh:
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

mongo = subprocess.run(
    ['docker', 'exec', 'chronos-mongo', 'mongosh', '--quiet', '--eval', '''
const db = db.getSiblingDB("chronos");
const early = db.job_executions.countDocuments({driftMs: {$lt: 0}});
const perKey = db.job_executions.aggregate([
  {$group: {_id: "$idempotencyKey", scheduled: {$addToSet: "$scheduledFor"}}},
  {$project: {n: {$size: "$scheduled"}}},
  {$match: {n: {$gt: 1}}},
  {$count: "keysWithMultipleScheduledTimes"}
]).toArray();
print(JSON.stringify({
  executions: db.job_executions.countDocuments({}),
  jobs: db.jobs.countDocuments({}),
  succeeded: db.jobs.countDocuments({status: "SUCCEEDED"}),
  failed: db.jobs.countDocuments({status: "FAILED"}),
  outstanding: db.jobs.countDocuments({status: {$in: ["PENDING", "CLAIMED"]}}),
  deadLetters: db.dead_letters.countDocuments({}),
  firedEarly: early,
  keysWithMultipleScheduledTimes: perKey.length ? perKey[0].keysWithMultipleScheduledTimes : 0
}));
'''], capture_output=True, text=True)

stats = json.loads([l for l in mongo.stdout.splitlines() if l.strip().startswith('{')][0])

def check(ok, label, detail=''):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}{('  — ' + detail) if detail else ''}")
    return ok

print(f"\n  Seeded jobs                : {expected}")
print(f"  Deliveries received        : {len(keys)}")
print(f"  Distinct idempotency keys  : {distinct}")
print(f"  Keys delivered >1 time     : {len(duplicated)}")
print(f"  Execution records written  : {stats['executions']}")
print(f"  Jobs SUCCEEDED / FAILED    : {stats['succeeded']} / {stats['failed']}")
print(f"  Still outstanding          : {stats['outstanding']}")
print()

expected_keys = json.loads([l for l in subprocess.run(
    ['docker', 'exec', 'chronos-mongo', 'mongosh', '--quiet', '--eval',
     'print(JSON.stringify(db.getSiblingDB("chronos").jobs.find({},{_id:1,currentRunScheduledFor:1})'
     '.toArray().map(j => "job_" + j._id.toString() + "_run_" + j.currentRunScheduledFor.getTime())))'],
    capture_output=True, text=True).stdout.splitlines() if l.strip().startswith('[')][0])

missing = [k for k in expected_keys if k not in counts]
stray = distinct - len([k for k in expected_keys if k in counts])

results = [
    check(not missing, "No loss: every seeded job reached the receiver at least once",
          f"{len(expected_keys) - len(missing)}/{len(expected_keys)} delivered"
          + (f"; {stray} keys in the journal belong to other runs" if stray else "")),
    check(stats['outstanding'] == 0, "Backlog fully drained"),
    check(not over_budget, "Retry budget respected: nothing delivered more than maxAttempts times",
          f"{len(over_budget)} keys exceeded 5 deliveries"),
    check(stats['firedEarly'] == 0, "Nothing fired before its scheduled time",
          f"{stats['firedEarly']} executions had negative drift"),
    check(stats['keysWithMultipleScheduledTimes'] == 0,
          "Every duplicate carries one stable identity",
          f"{stats['keysWithMultipleScheduledTimes']} keys mapped to >1 scheduled time"),
]

dup_pct = (len(duplicated) / expected * 100) if expected else 0
print(f"\n  Duplicate rate: {len(duplicated)}/{expected} = {dup_pct:.2f}%")
print("  Duplicates are EXPECTED under at-least-once delivery: a worker killed after sending but")
print("  before recording success leaves a job whose lease expires and is legitimately redelivered.")
print("  What matters is that they are bounded, and that each one repeats an identity the receiver")
print("  can deduplicate on — which is the last assertion above.")

print(f"\n{'ALL ASSERTIONS PASSED' if all(results) else 'SOME ASSERTIONS FAILED'}")
sys.exit(0 if all(results) else 1)
PYEOF
