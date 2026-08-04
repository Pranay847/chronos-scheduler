// k6 load profile for job creation.
//
//   docker run --rm --network chronos-scheduler_default \
//     -v "$PWD/load:/scripts" grafana/k6 run /scripts/create-jobs.js
//
// Uses a RAMPING ARRIVAL RATE rather than a fixed number of virtual users, and the distinction
// matters for what the result means. Fixed VUs are closed-loop: each one waits for its response
// before sending again, so if the service slows down the offered load slows with it and the system
// never actually gets pushed past its limit — you measure a comfortable equilibrium and call it
// capacity. Arrival rate is open-loop: requests are offered at the target rate whether or not the
// previous ones came back, which is how real traffic behaves and the only way to find where the
// service breaks rather than where it prefers to sit.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const createLatency = new Trend('job_create_latency', true);
const createFailed = new Rate('job_create_failed');

const BASE = __ENV.BASE || 'http://worker:8080';
const SINK = __ENV.SINK || 'http://sink:8080';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 400,
      stages: [
        { target: 100, duration: '20s' },   // ~6k jobs/min
        { target: 400, duration: '20s' },   // ~24k jobs/min
        { target: 1000, duration: '30s' },  // ~60k jobs/min — expected to break somewhere here
      ],
    },
  },
  thresholds: {
    // Deliberately not a pass/fail gate on the highest stage. The point of this run is to find the
    // ceiling, and a run that "fails" at the rate it was designed to exceed tells you nothing.
    job_create_latency: ['p(95)<2000'],
  },
};

export default function () {
  // Scheduled a minute out, so creation throughput is measured on its own rather than competing
  // with the pollers for the same database.
  const runAt = new Date(Date.now() + 60000).toISOString();

  const payload = JSON.stringify({
    name: `k6-${__VU}-${__ITER}`,
    schedule: { type: 'ONE_TIME', runAt: runAt },
    target: { url: `${SINK}/hook`, method: 'POST', payload: { n: __ITER } },
  });

  const res = http.post(`${BASE}/v1/jobs`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  createLatency.add(res.timings.duration);
  createFailed.add(res.status !== 201);
  check(res, { 'created': (r) => r.status === 201 });
}
