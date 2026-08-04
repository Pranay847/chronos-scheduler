# Deploying to Fly.io — free tier

An alternative to [`DEPLOYMENT.md`](DEPLOYMENT.md)'s AWS App Runner + Atlas M10 path, for a
free, publicly-reachable instance rather than a production one. Read the tradeoffs section before
running this against anything real.

Nothing in this file has been executed — creating a Fly account and an Atlas cluster both need
credentials only you have, and Fly's free allowance still requires a card on file even at $0
billed. This is the exact sequence to run once you've done that part.

---

## 1. Atlas M0 (free forever, no card required for the cluster itself)

1. [cloud.mongodb.com](https://cloud.mongodb.com) → new project → **M0** cluster.
2. Database Access → add a user, `readWrite` on the `chronos` database.
3. Network Access → **Allow access from anywhere** (`0.0.0.0/0`). M0 has no private networking or
   VPC peering, so this is the only option — a real limitation, noted below.
4. Copy the connection string: `mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/chronos`

## 2. Fly CLI and account

```bash
curl -L https://fly.io/install.sh | sh      # or: iwr https://fly.io/install.ps1 -useb | iex
flyctl auth signup                          # card required for the free allowance, $0 charged within it
```

## 3. Launch

`fly.toml` already exists in the repo, so this attaches to it rather than generating a new one:

```bash
flyctl launch --no-deploy --copy-config --name chronos-scheduler-<yoursuffix>
```

`<yoursuffix>` because `chronos-scheduler` as an app name is almost certainly taken — Fly app
names are global.

## 4. The connection string is a secret, not config

```bash
flyctl secrets set SPRING_MONGODB_URI="mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/chronos?retryWrites=true&w=majority"
```

Never in `fly.toml` — that file is committed to the repo.

## 5. Deploy

```bash
flyctl deploy
```

Builds from the same `Dockerfile` used everywhere else in this project, so there is no separate
Fly-specific image to keep in sync.

## 6. Verify

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

| | `DEPLOYMENT.md` (App Runner + Atlas M10) | This (Fly free + Atlas M0) |
|---|---|---|
| Cost | ~$80–110/mo | $0, within the free allowances |
| Change streams | on | **off** — see `fly.toml`; M0's shared oplog can roll over faster than a worker resumes, and [BENCHMARKS.md §4](../BENCHMARKS.md) shows the feature is close to a wash at 200ms anyway |
| Network access | VPC-scoped | **`0.0.0.0/0`** — M0 has no private networking. The database is reachable from anywhere with the password; there is no network-layer boundary, only the credential |
| Fleet size | 3+ workers (the point of the project) | 1 machine |
| `w:majority` cost | re-measure on Atlas — see `DEPLOYMENT.md` step 1 | still real, still worth checking, but on shared M0 hardware rather than dedicated |

**The fleet-size row is the one worth being upfront about.** The atomic claim and lease-based
recovery work identically at one worker or thirty — nothing in the design assumes multiple
instances. But "no coordination needed to scale" is much easier to *believe* watching three
containers split work with nothing telling them how, which is why `docker compose up --scale
worker=3` stays the way to actually demonstrate it, chaos test included. Free hosting gets you a
reachable URL; it doesn't reproduce the multi-worker story on its own.

**Both are real trades, not just "the free version."** Say so if anyone asks why a public demo
runs one instance against an openly-reachable free database — that's a correct answer, and a
better one than pretending it's identical to the production path in `DEPLOYMENT.md`.
