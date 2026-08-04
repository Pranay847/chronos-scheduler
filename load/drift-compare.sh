#!/usr/bin/env bash
#
# Change-stream drift comparison.
#
#   ./load/drift-compare.sh [JOB_COUNT]
#
# Runs the identical workload twice — once with the change-stream wakeup on, once with it off —
# and reports drift from the execution records. Same jobs, same fleet, one variable.
#
# Reads drift from job_executions rather than from Prometheus, because a before/after needs the two
# runs cleanly separated and a scrape window blurs them together.

set -uo pipefail
JOBS=${1:-300}
POLL_MS=${2:-200}

say() { printf '\n\033[1m=== %s\033[0m\n' "$*"; }

run_case() {
  local label="$1" enabled="$2"

  docker compose stop worker >/dev/null 2>&1
  docker exec chronos-mongo mongosh --quiet --eval \
    'const d=db.getSiblingDB("chronos"); d.jobs.deleteMany({}); d.job_executions.deleteMany({});' >/dev/null

  CHRONOS_CHANGE_STREAM_ENABLED="$enabled" CHRONOS_POLL_INTERVAL_MS="$POLL_MS" docker compose up -d --scale worker=3 >/dev/null 2>&1
  until [ "$(docker compose ps --status running --format '{{.Service}}' | grep -c '^worker$')" -ge 3 ]; do sleep 2; done
  sleep 12   # workers up, change stream (if enabled) subscribed

  # Every job due 3 seconds out, individually. That is the case the wakeup targets: known in
  # advance, imminent, and otherwise waiting for the next poll tick.
  docker exec chronos-mongo mongosh --quiet --eval "
  const db = db.getSiblingDB('chronos');
  const now = Date.now(), docs = [];
  for (let i = 0; i < $JOBS; i++) {
    const at = new Date(now + 3000 + i * 20);
    docs.push({_class:'dev.pranay.chronos.domain.Job', tenantId:'default', name:'drift-'+i,
      schedule:{type:'ONE_TIME',runAt:at,cronExpression:null,timezone:'UTC',misfirePolicy:'FIRE_ONCE'},
      target:{url:'http://sink:8080/hook',method:'POST',headers:{},payload:{n:i},timeoutMs:5000},
      retryPolicy:{maxAttempts:5,backoffBaseMs:1000,backoffMaxMs:300000},
      status:'PENDING', nextRunAt:at, currentRunScheduledFor:at,
      attempt:0, claimCount:0, version:NumberLong('0'), lockedBy:null, lockExpiresAt:null,
      lastRunAt:null, lastStatusCode:null, lastError:null, triggeredFrom:null,
      completedAt:null, createdAt:new Date(), updatedAt:new Date()});
  }
  db.jobs.insertMany(docs, {ordered:false});" >/dev/null

  local deadline=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local left
    left=$(docker exec chronos-mongo mongosh --quiet --eval \
      "db.getSiblingDB('chronos').jobs.countDocuments({status:{\$in:['PENDING','CLAIMED']}})" 2>/dev/null | tr -d '\r')
    [ "$left" = "0" ] && break
    sleep 3
  done

  docker exec chronos-mongo mongosh --quiet --eval "
  const db = db.getSiblingDB('chronos');
  const d = db.job_executions.find({}, {driftMs:1}).toArray().map(e => Number(e.driftMs)).sort((a,b)=>a-b);
  if (!d.length) { print(JSON.stringify({label:'$label', error:'no executions'})); quit(); }
  const pct = p => d[Math.min(d.length-1, Math.floor(d.length*p))];
  print(JSON.stringify({
    label: '$label', n: d.length,
    min: d[0], p50: pct(0.50), p95: pct(0.95), p99: pct(0.99), max: d[d.length-1],
    mean: Math.round(d.reduce((a,b)=>a+b,0)/d.length)
  }));" 2>&1 | grep -E '^\{'
}

say "Baseline: polling only (change stream disabled)"
run_case "polling only" false

say "With change-stream wakeup"
run_case "change stream" true

say "Done — $JOBS jobs, 3 workers, poll interval ${POLL_MS}ms"
