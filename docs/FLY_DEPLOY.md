# Deploying to Fly.io — free tier

An alternative to [`DEPLOYMENT.md`](DEPLOYMENT.md)'s AWS App Runner + Atlas M10 path, for a
free, publicly-reachable instance rather than a production one. Read the tradeoffs section before
running this against anything real.

Both options below were attempted for real, and **neither has yet produced a working deployment** —
Option A is blocked by something outside this project's control, Option B by Fly's billing rules.
What follows is what was actually observed, including the dead ends, because the dead ends are the
part that would otherwise cost someone else the same afternoon.

**Verified status, precisely:**

| Step | Option A (Atlas M0) | Option B (self-hosted on Fly) |
|---|---|---|
| Database reachable from Fly | ❌ TLS rejected at Atlas's edge | ✅ replica set reached `PRIMARY` |
| Scheduler connects to it | ❌ never succeeded | ⚠️ **not yet verified** — blocked before this point |
| Health checks pass | ❌ | ⚠️ not yet reached |

---

## Option A: Atlas M0 — free, but does not currently work from Fly

1. [cloud.mongodb.com](https://cloud.mongodb.com) → new project → **M0** cluster.
2. Database Access → add a user, `readWrite` on the `chronos` database.
3. Network Access → **Allow access from anywhere** (`0.0.0.0/0`). M0 has no private networking or
   VPC peering, so this is the only option — a real limitation, noted below.
4. Copy the connection string: `mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/chronos`

**This was tried first, and the TLS handshake fails specifically from Fly's network.** The
symptom: `javax.net.ssl.SSLException: (internal_error) Received fatal alert: internal_error` from
the JVM driver against all three Atlas shard hosts. Forcing TLSv1.2 client-side did not fix it —
identical alert. The isolating test: the exact same `openssl s_client -tls1_2` handshake against
the exact same shard IP, from the exact same client, **succeeds outside Fly's network** (full
certificate chain validates) and **fails identically to the JVM from inside a Fly machine**
(`tlsv1 alert internal error`, alert number 80) — same IP, same port, same TLS version, only the
network path differs. That isolates the fault to Atlas's edge rejecting the handshake based on
source IP/network reputation, not a JVM/driver/cert/credentials problem. No JVM flag fixes a
server-side rejection. If you hit this and want to keep using Atlas, the options are contacting
Atlas support about the specific egress range, or trying a different Fly region — untested here,
since Option B turned out to be the more direct fix.

## Option B: self-hosted Mongo on a second Fly Machine (recommended, not yet completed)

Skips the public internet — and Atlas's edge — entirely by running MongoDB on Fly's private `6PN`
network, reachable only from other apps in the same org. A single-node replica set satisfies
`w:majority` the same way Testcontainers and docker-compose already do here, so nothing about the
app's write-concern story changes.

```bash
flyctl apps create chronos-mongo-<yoursuffix>
flyctl volumes create mongo_data --app chronos-mongo-<yoursuffix> --region iad --size 1
```

### §3b. The trial-org billing wall

Two things about a no-card Fly org bit us here, both worth knowing before you start:

- **`flyctl deploy` for a new release is flatly rejected** on a trial org: `"This functionality is
  disabled for trial organizations."` This is unrelated to usage or cost — it blocks the managed
  deploy path (the one that lets `[[services]]` config actually control machine lifecycle) even
  at zero billed dollars.
- **A raw `flyctl machine run` machine (no `fly deploy`, no services block) gets silently stopped
  by Fly's own orchestrator (`flyd`) roughly 5 minutes after every start**, `requested_stop=true`,
  regardless of `flyctl machine update --autostop=off`. That flag is a no-op here: it configures
  the fly-proxy's service-driven autostop, and a machine with no `[[services]]` has no service for
  the flag to attach to. This pattern cost real debugging time — it looks exactly like a crash
  (machine state flips to `stopped`) but the exit code is clean and the source is `flyd`, not the
  app.

Net effect: self-hosting Mongo on Fly **for real, unattended uptime** needs a card on the org,
same as the managed-deploy path. Add one at `fly.dashboard → org → Billing` — Fly's free
allowances (3× `shared-cpu-1x-256mb`, 3GB volume storage, 160GB outbound) should keep this at $0.
Once it's there, `fly deploy` works normally and the `[[services]]` autostop config below actually
takes effect.

### Launch Mongo as a managed app

The config lives in the repo at [`deploy/fly-mongo/fly.toml`](../deploy/fly-mongo/fly.toml) — a
separate app from the scheduler's own `fly.toml`, deployed independently:

```toml
app = 'chronos-mongo-<yoursuffix>'
primary_region = 'iad'

[build]
  image = 'mongo:7'

[processes]
  app = 'mongod --replSet rs0 --bind_ip_all'

[mounts]
  source = 'mongo_data'
  destination = '/data/db'

[[services]]
  protocol = 'tcp'
  internal_port = 27017
  processes = ['app']
  auto_stop_machines = 'off'
  auto_start_machines = true
  min_machines_running = 1

  [[services.ports]]
    port = 27017
```

No public IP is allocated for this app — the `[[services.ports]]` block is required to pass Fly's
config validation, but without an allocated IP nothing routes to it from the public internet.
Reachability stays limited to other apps in the same org, over `6PN`.

```bash
flyctl deploy --app chronos-mongo-<yoursuffix> --config deploy/fly-mongo/fly.toml
```

### Initiate the replica set, pinned to a stable address

```bash
flyctl ssh console --app chronos-mongo-<yoursuffix> -C "mongosh --quiet --eval 'rs.initiate()'"
```

`rs.initiate()` with no argument self-identifies the member using the **container's bare
hostname** (the Fly machine ID, e.g. `82d1440b7561d8:27017`) — not a resolvable name from anywhere
else. Combine that with `<app>.internal` DNS occasionally lagging or failing to resolve forward
even when the matching PTR record resolves fine (`getent hosts <app>.internal` returned nothing
while `getent hosts <the machine's private IPv6>` correctly reverse-resolved), and the driver's
topology monitor can end up chasing an address it can never reach. The intended fix — **designed
from the observed failure but not yet confirmed working**, since the billing wall below stopped
the run before it could be applied — is to reconfigure the replica set member to the machine's
**private IPv6 literal** rather than any DNS name, and point the app's connection string at the
same literal:

```js
cfg = rs.conf();
cfg.members[0].host = "[fdaa:...:...:...]:27017";   // the machine's private IPv6, from `flyctl machine status`
rs.reconfig(cfg, {force: true});
```

```bash
flyctl secrets set --app chronos-scheduler-<yoursuffix> \
  "SPRING_MONGODB_URI=mongodb://[fdaa:...:...:...]:27017/chronos?replicaSet=rs0"
```

The private IPv6 is stable across start/stop as long as the machine itself isn't destroyed and
recreated — which is also why the `auto_stop_machines = 'off'` / `min_machines_running = 1` pair
in the services block matters beyond just uptime: destroying and recreating the machine reassigns
the address and breaks the pinned connection string until it's updated again.

## Fly CLI and account, for the scheduler app itself

```bash
curl -L https://fly.io/install.sh | sh      # or: iwr https://fly.io/install.ps1 -useb | iex
flyctl auth signup                          # card required for the free allowance, $0 charged within it
```

### Launch

`fly.toml` already exists in the repo, so this attaches to it rather than generating a new one:

```bash
flyctl launch --no-deploy --copy-config --name chronos-scheduler-<yoursuffix>
```

`<yoursuffix>` because `chronos-scheduler` as an app name is almost certainly taken — Fly app
names are global.

### The connection string is a secret, not config

Point this at whichever database Option A or B above left you with:

```bash
flyctl secrets set --app chronos-scheduler-<yoursuffix> \
  SPRING_MONGODB_URI="<connection string from Option A or B>"
```

Never in `fly.toml` — that file is committed to the repo.

### Deploy

```bash
flyctl deploy --app chronos-scheduler-<yoursuffix>
```

Builds from the same `Dockerfile` used everywhere else in this project, so there is no separate
Fly-specific image to keep in sync.

### Verify

```bash
curl https://chronos-scheduler-<yoursuffix>.fly.dev/actuator/health
```

Then repeat the checks from [`docker-compose.prod-check.yml`](../docker-compose.prod-check.yml)
against the real URL — create a tenant, confirm the SSRF guard rejects
`169.254.169.254`, confirm a bad key is rejected:

```bash
curl -X POST https://chronos-scheduler-<yoursuffix>.fly.dev/v1/tenants \
  -H 'Content-Type: application/json' -d '{"name":"demo"}'
```

---

## What is different from a real deployment, and why it's free

| | `DEPLOYMENT.md` (App Runner + Atlas M10) | This (Fly free + self-hosted Mongo, Option B) |
|---|---|---|
| Cost | ~$80–110/mo | $0, within the free allowances (card on file required, see §3b) |
| Change streams | on | **off** — see `fly.toml`; a single-node replica set on shared hardware makes the polling-vs-streams gap even less worth the complexity, and [BENCHMARKS.md §4](../BENCHMARKS.md) shows the feature is close to a wash at 200ms anyway |
| Network access | VPC-scoped | Fly `6PN` private network only — no public IP allocated to the Mongo app, so unlike Atlas M0's `0.0.0.0/0` there genuinely is a network-layer boundary, not just a credential |
| Fleet size | 3+ workers (the point of the project) | 1 machine |
| `w:majority` cost | re-measure on Atlas — see `DEPLOYMENT.md` step 1 | still real, still worth checking, but on a single-node replica set rather than a true multi-node one — majority-of-one is a much weaker guarantee than production Atlas's majority-of-three |

**The fleet-size row is the one worth being upfront about.** The atomic claim and lease-based
recovery work identically at one worker or thirty — nothing in the design assumes multiple
instances. But "no coordination needed to scale" is much easier to *believe* watching three
containers split work with nothing telling them how, which is why `docker compose up --scale
worker=3` stays the way to actually demonstrate it, chaos test included. Free hosting gets you a
reachable URL; it doesn't reproduce the multi-worker story on its own.

**Both are real trades, not just "the free version."** Say so if anyone asks why a public demo
runs one instance against an openly-reachable free database — that's a correct answer, and a
better one than pretending it's identical to the production path in `DEPLOYMENT.md`.
