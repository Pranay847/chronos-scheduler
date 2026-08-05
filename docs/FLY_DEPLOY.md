# Deploying to Fly.io — free tier

An alternative to [`DEPLOYMENT.md`](DEPLOYMENT.md)'s AWS App Runner + Atlas M10 path, for a
free, publicly-reachable instance rather than a production one. Read the tradeoffs section before
running this against anything real.

> **If you just want a free public instance, use [`RENDER_DEPLOY.md`](RENDER_DEPLOY.md) instead** —
> that path is deployed and verified. This file is kept for one debugging lesson, described below.

## The mistake this file exists to record

Deploying here failed with a TLS error, and **I diagnosed it wrong, confidently, and acted on the
wrong diagnosis for hours.** The wrong conclusion was: *Atlas rejects TLS handshakes from Fly's
network based on source-IP reputation.* That is not what was happening.

**What was actually happening:** the Atlas project's IP Access List contained a single entry — the
developer's home IP, added automatically by Atlas's setup wizard. Atlas does not reject a
non-allowlisted client with an authorization error. It **terminates the TLS handshake with a generic
`internal_error` alert**, which is indistinguishable from a genuine TLS incompatibility:

```
javax.net.ssl.SSLException: (internal_error) Received fatal alert: internal_error
```

**Why the "proof" was worthless.** The isolating test was an `openssl s_client -tls1_2` handshake
against the same shard IP from two networks: it succeeded locally and failed from inside a Fly
machine. Same IP, same port, same TLS version — so the network path *must* be the variable. The
reasoning was airtight and the conclusion was still wrong, because the local machine was the one
network in the world that was allowlisted. The experiment established "these two networks differ"
and nothing else; the *reason* for the difference was invented, not measured.

**What exposed it:** redeploying on Render — a different company, a different network — produced the
byte-identical error. "Atlas blocks Fly specifically" cannot explain that. One observation the
hypothesis couldn't survive was worth more than the elaborate test that appeared to confirm it.

**The lesson worth keeping:** a control test only controls for the variables you thought of. When an
error message is generic (`internal_error` names nothing), treat any confident causal story about it
as a hypothesis awaiting a second, independent observation — especially when the story is
unfalsifiable from where you're standing. The cheap check here was one click: look at the access
list. It was never done, because the expensive test felt conclusive.

The fix was adding `0.0.0.0/0` to the IP Access List. **Fly was never the problem** — this path
would likely have worked. It was abandoned for Fly's billing restrictions (below), not for TLS.

---

## Option A: Atlas M0 + Fly

1. [cloud.mongodb.com](https://cloud.mongodb.com) → new project → **M0** cluster.
2. Database Access → add a user, `readWrite` on the `chronos` database.
3. Network Access → **Allow access from anywhere** (`0.0.0.0/0`). **Do not skip this.** Atlas's
   setup wizard allowlists only the IP you signed up from, and every hosted platform will be a
   different IP. M0 has no private networking or VPC peering, so `0.0.0.0/0` is the only option —
   a real weakness, noted below.
4. Copy the connection string, and **put the database name in it**:
   `mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/chronos`. With a bare `/` before the `?`,
   Spring fails at startup with `Database name must not be empty`.

Untested end-to-end on Fly, since the billing wall below stopped the run before the access list was
understood. Nothing is known to be wrong with it.

## Option B: self-hosted Mongo on a second Fly Machine

**This was built to work around a problem that did not exist** (see above). It is still a legitimate
architecture — running MongoDB on Fly's private `6PN` network means the database has no public
listener at all, which is strictly better than Atlas M0's `0.0.0.0/0`-or-nothing. But it was reached
by bad reasoning, and it is more moving parts than a demo needs. A single-node replica set satisfies
`w:majority` the same way Testcontainers and docker-compose already do, so the write-concern story
is unchanged.

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
