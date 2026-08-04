# Deployment

AWS App Runner + MongoDB Atlas. Written to be followed once and then automated by
[`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml).

> Everything here costs money and touches accounts only you control, so nothing in this file has
> been executed — it is a runbook, not a record. The App Runner free tier is limited and Atlas M0 is
> free but slow; check current pricing before running it.

---

## The configuration that must change from the demo stack

`docker-compose.yml` sets two switches that exist purely so a local stack can deliver to a container
on a private address. **Both must revert to their defaults in production**, and the application logs
a warning at startup whenever either is off.

| Property | Demo | Production | Consequence if wrong |
|---|---|---|---|
| `chronos.security.allow-private-targets` | `true` | **`false`** | The service becomes an open proxy into your VPC. Anyone who can create a job can reach your internal network and your instance metadata endpoint. |
| `chronos.security.require-api-key` | `false` | **`true`** | Every request is treated as one shared tenant. No isolation, no rate limiting per customer. |

Neither is set in `application.properties`, so a deployment that simply doesn't pass them gets the
secure values. That is deliberate — the failure mode of forgetting is safe.

---

## 1. MongoDB Atlas

Create an **M10 or larger** — not M0.

M0 is a shared tier with no dedicated resources and, more importantly for this service, oplog
behaviour you do not control. Change streams read the oplog, and a shared-tier oplog can roll over
faster than a worker can resume from its token.

```
Replica set:      3 nodes (Atlas default)
Network access:   VPC peering, or the App Runner egress addresses. NOT 0.0.0.0/0.
Database user:    readWrite on `chronos` only
```

Connection string:

```
mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/chronos?retryWrites=true&w=majority
```

`w=majority` matters here in a way it never did locally. On the single-node replica set the whole
project was built against, "majority" is one node — so the durability guarantee cost 12.3ms per
insert but never actually replicated anywhere ([BENCHMARKS.md §3](../BENCHMARKS.md)).

**Re-measure it on Atlas.** Against three nodes with real network replication that number will be
meaningfully different, and it is the dominant cost in job creation. If throughput matters more than
the guarantee, the honest lever is `w:1` on *creation* while keeping majority on the *claim* — the
write where losing an acknowledgement actually changes behaviour.

---

## 2. ECR

```bash
aws ecr create-repository \
  --repository-name chronos-scheduler \
  --image-scanning-configuration scanOnPush=true \
  --region us-east-1
```

---

## 3. The deploy role — OIDC, not an access key

The deploy workflow assumes a role via GitHub's OIDC provider, so there is no long-lived
`AWS_SECRET_ACCESS_KEY` in repository secrets. A static key in a repo's secrets is one
misconfigured workflow away from being printed into a public build log, and it stays valid
afterwards.

Register GitHub as an OIDC provider (once per account):

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

Trust policy on the deploy role — note the `sub` condition is scoped to **one repository and one
branch**. Leaving it as `repo:you/*` would let any repository in your account assume it:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike":   { "token.actions.githubusercontent.com:sub": "repo:<OWNER>/chronos-scheduler:ref:refs/heads/main" }
    }
  }]
}
```

Permissions: ECR push, plus `apprunner:StartDeployment` and `apprunner:DescribeService` on the one
service ARN.

---

## 4. App Runner

Point the service at the ECR image, port 8080, health check `/actuator/health`.

Environment — note what is *absent* as much as what is present:

```
SPRING_MONGODB_URI          = <Atlas connection string>   (secret)
JAVA_TOOL_OPTIONS           = -XX:MaxRAMPercentage=75
CHRONOS_POLL_INTERVAL_MS    = 200
CHRONOS_LEASE_DURATION_MS   = 30000
```

`CHRONOS_SECURITY_*` is deliberately unset, so both switches take their secure defaults.

Store the connection string in Secrets Manager and reference it, rather than pasting it into the
service configuration where it is visible in the console and in `describe-service` output.

### Sizing the lease for a real network

The lease inequality has terms that were all near-zero locally:

```
lease > queueWait(p99) + connectTimeout + requestTimeout + reaperPeriod + clockSkew
```

`LeaseSanityCheck` enforces only `lease ≥ 3 × timeout` at startup, because the rest is empirical.
Watch **`scheduler_lease_lost_total`** after deploying: any sustained non-zero rate means real queue
wait has outgrown the lease, and every one of those is a duplicate delivery.

---

## 5. Repository secrets

| Secret | Value |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | the role from step 3 |
| `APP_RUNNER_SERVICE_ARN` | the service from step 4 |

No AWS keys. That is the point of step 3.

---

## 6. Deploy

Actions → **Deploy** → type `deploy`. Manual on purpose: a scheduler firing real webhooks at real
customer endpoints should not redeploy on every merge until there is a staging environment to catch
a bad build first.

The workflow pushes the image tagged with the commit SHA as well as `latest`, so a rollback is a
redeploy of a known image rather than a rebuild of a hopefully-identical one. It then waits for
`RUNNING` and smoke-tests `/actuator/health` — App Runner reports RUNNING as soon as the container is
up, which is before the application has proven it can reach MongoDB.

---

## After the first deploy

1. **Create a real tenant** and store the key. It is shown once and only its hash is persisted.
   ```bash
   curl -X POST https://<service-url>/v1/tenants -H 'Content-Type: application/json' -d '{"name":"acme"}'
   ```
2. **Confirm both switches are on** — the startup log must contain neither
   `SSRF PROTECTION DISABLED` nor `API KEY AUTHENTICATION DISABLED`.
3. **Verify the SSRF guard from outside**: a job targeting `http://169.254.169.254/latest/meta-data/`
   must be rejected with 400.
4. **Point Prometheus at it** and re-measure drift. The dashboard queries are unchanged; only the
   scrape target differs.
5. **Re-run the write-concern measurement** against Atlas, and update
   [BENCHMARKS.md](../BENCHMARKS.md) with the real number.

---

## Not covered

- **Horizontal scale.** App Runner autoscaling works — the workers are identical and coordinate
  through the database — but every added instance multiplies the per-worker rate limit and circuit
  breaker, both of which are per-worker by design.
- **Backups.** Atlas handles snapshots; retention is a business decision.
- **`jobs` growth.** There is a TTL on `job_executions` but not on terminal jobs. At sustained
  volume that collection grows without bound and degrades `idx_claim`. Decide on TTL or archival
  before running this at scale, not after.
