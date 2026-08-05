# Deploying to Render — free, no card

The path that actually works for a free public instance. [`FLY_DEPLOY.md`](FLY_DEPLOY.md) documents
why the two Fly routes did not, and [`DEPLOYMENT.md`](DEPLOYMENT.md) is the real production runbook
(AWS App Runner + Atlas M10) if this ever needs to be more than a demo.

Render's free tier needs no payment method, builds the same `Dockerfile` everything else here uses,
and gives an HTTPS URL. **Read the last section before showing it to anyone** — a free instance
sleeps, and "a scheduler that sleeps" deserves an honest explanation rather than a footnote.

---

## 1. Database: Atlas M0

If you already made one for the Fly attempt, reuse it — the cluster was never the problem there.

1. [cloud.mongodb.com](https://cloud.mongodb.com) → new project → **M0** (free forever, no card).
2. Database Access → add a user with `readWrite` on the `chronos` database.
3. Network Access → **Allow access from anywhere** (`0.0.0.0/0`). M0 has no private networking, so
   this is the only option. It is a real weakness and it is listed as one below.
4. Copy the connection string and append the database name and write concern:

   ```
   mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/chronos?retryWrites=true&w=majority
   ```

## 2. Deploy from the Blueprint

[`render.yaml`](../render.yaml) in the repo root configures the service, so this is mostly clicking:

1. [render.com](https://render.com) → sign up with GitHub (no card).
2. **New → Blueprint** → pick this repository.
3. Render reads `render.yaml` and shows one field to fill: `SPRING_MONGODB_URI`. Paste the string
   from step 1. It is marked `sync: false` precisely so it lives in Render's encrypted env store
   and never in the repo.
4. **Apply**. First build is slow — Gradle resolves dependencies from scratch, so budget 5–10
   minutes. Subsequent deploys reuse Docker layers.

## 3. Verify

Replace `<your-url>` with the assigned `*.onrender.com` hostname.

```bash
curl https://<your-url>/actuator/health
```

Then the three checks worth actually running, because each one proves a claim the README makes:

```bash
# 1. Auth is enforced — expect 401, not 200.
curl -i https://<your-url>/v1/jobs

# 2. Bootstrap a tenant (this endpoint is deliberately unauthenticated — see README).
curl -X POST https://<your-url>/v1/tenants \
  -H 'Content-Type: application/json' -d '{"name":"demo"}'
# → returns an API key. It is shown once; only its hash is stored.

# 3. The SSRF guard rejects cloud metadata — expect 400, not a fired webhook.
curl -i -X POST https://<your-url>/v1/jobs \
  -H "Authorization: Bearer <key from step 2>" -H 'Content-Type: application/json' \
  -d '{"name":"ssrf-probe",
       "schedule":{"type":"ONE_TIME","runAt":"2030-01-01T00:00:00Z"},
       "target":{"url":"http://169.254.169.254/latest/meta-data/"}}'
```

That third one is the check worth keeping in your back pocket. `169.254.169.254` is the AWS/GCP/Azure
instance metadata address; an unguarded webhook scheduler will happily fetch credentials from it and
POST them wherever the job says to.

---

## What a free instance actually costs you

| | Production (`DEPLOYMENT.md`) | This |
|---|---|---|
| Cost | ~$80–110/mo | $0, no card |
| Uptime | always on | **sleeps after ~15 min idle**, 30–60s cold start |
| Fleet size | 3+ workers | 1 instance |
| Memory | sized for the workload | 512MB, JVM heap capped at 75% (see `render.yaml`) |
| Database reachability | VPC-scoped | `0.0.0.0/0` — the password is the only boundary |
| Change streams | on | off |

### The sleeping-scheduler problem, stated plainly

**A sleeping instance does not fire scheduled jobs.** For a project whose headline metric is
*236ms p99 scheduling drift*, that is not a small asterisk, and it should be said out loud rather
than discovered by whoever you sent the link to.

What actually happens is more interesting than "it breaks", though:

- Jobs are stored in Mongo with `nextRunAt`, not held in memory. Sleeping loses no state.
- On wake, the poller's very next tick claims everything already overdue and fires it.
- A cron job that missed six firings does **not** stampede six webhooks on wake: the default
  [`MisfirePolicy.FIRE_ONCE`](../src/main/java/dev/pranay/chronos/domain/MisfirePolicy.java)
  collapses missed slots into a single firing and resumes the normal schedule. `FIRE_ALL` is opt-in,
  for when every tick has independent meaning.

So the failure mode is **late, not lost, and not a thundering herd** — which is the misfire policy
doing exactly the job it was written for, exercised by an infrastructure quirk rather than a test.

That degradation is a property of the design rather than a lucky accident, and it is a better answer
than pretending the demo is production. **If you need to demonstrate the drift numbers, do it with
`docker compose up --scale worker=3` and [`BENCHMARKS.md`](../BENCHMARKS.md)** — that is where the
measurements came from, and a single sleeping free instance cannot reproduce them.

Use this deployment to prove the API is real, the auth works, and the SSRF guard bites. Use the
local stack to prove the scheduling.
