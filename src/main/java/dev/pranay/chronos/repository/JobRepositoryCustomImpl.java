package dev.pranay.chronos.repository;

import com.mongodb.client.result.UpdateResult;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The heart of the scheduler. Everything else is plumbing.
 */
public class JobRepositoryCustomImpl implements JobRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public JobRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<Job> claimNextDueJob(String workerId, Duration leaseDuration) {
        Instant now = Instant.now();

        // status is an equality match and nextRunAt is a range + the sort key. That is exactly
        // the shape idx_claim is built for, and the reason its fields are in that order.
        Query query = new Query(
                Criteria.where("status").is(JobStatus.PENDING)
                        .and("nextRunAt").lte(now)
        ).with(Sort.by(Sort.Direction.ASC, "nextRunAt"));

        Update update = new Update()
                .set("status", JobStatus.CLAIMED)
                .set("lockedBy", workerId)
                .set("lockExpiresAt", now.plus(leaseDuration))
                .set("updatedAt", now)
                // Pickups, not deliveries. A crash or a tripped circuit breaker increments this
                // and leaves `attempt` alone, so neither can spend the customer's retry budget.
                // `attempt` is incremented by the dispatcher, once it is genuinely about to send.
                .inc("claimCount", 1);

        FindAndModifyOptions options = FindAndModifyOptions.options()
                // Mandatory. Without it findAndModify returns the pre-update document, so the
                // caller sees a stale claimCount and a null lockExpiresAt on the object it is
                // about to act on.
                .returnNew(true);

        return Optional.ofNullable(mongoTemplate.findAndModify(query, update, options, Job.class));
    }

    @Override
    public boolean completeIfOwned(String jobId, String workerId, JobCompletion completion) {
        Instant now = Instant.now();

        // Re-asserting ownership is what makes this safe. The claim stopped a second worker from
        // STARTING this job; nothing stops a worker whose lease quietly expired mid-delivery from
        // FINISHING it. Without these two extra predicates, a slow worker returning late
        // overwrites the result of whoever legitimately owns the job now — and for a cron job it
        // would roll the schedule forward a second time, silently losing a firing.
        Query stillMine = new Query(
                Criteria.where("id").is(jobId)
                        .and("lockedBy").is(workerId)
                        .and("lockExpiresAt").gt(now)
        );

        Update update = new Update()
                .set("status", completion.status())
                .set("attempt", completion.attempt())
                .set("lastRunAt", completion.lastRunAt())
                .set("lastStatusCode", completion.statusCode())
                .set("completedAt", now)
                .set("updatedAt", now)
                .set("lockedBy", null)
                .set("lockExpiresAt", null);

        UpdateResult result = mongoTemplate.updateFirst(stillMine, update, Job.class);
        return result.getMatchedCount() > 0;
    }

    @Override
    public boolean rescheduleIfOwned(String jobId, String workerId, Instant nextRunAt, int attempt,
                                     Integer statusCode, String error) {
        Instant now = Instant.now();

        Query stillMine = new Query(
                Criteria.where("id").is(jobId)
                        .and("lockedBy").is(workerId)
                        .and("lockExpiresAt").gt(now)
        );

        Update update = new Update()
                .set("status", JobStatus.PENDING)
                .set("nextRunAt", nextRunAt)
                .set("attempt", attempt)
                .set("lastRunAt", now)
                .set("lastStatusCode", statusCode)
                .set("lastError", error)
                .set("updatedAt", now)
                .set("lockedBy", null)
                .set("lockExpiresAt", null);

        // currentRunScheduledFor is deliberately absent from that update, and this is the one
        // line in the retry path that the whole at-least-once contract rests on. nextRunAt moves
        // because we are scheduling another try; the firing itself has not changed, so its
        // scheduled time — and therefore its idempotency key — must stay exactly where it is.
        //
        // Setting both here is the natural-looking mistake. Nothing would fail, no test that
        // only checks the happy path would notice, and every retry would reach the receiver
        // wearing a brand new key. The receiver, following the deduplication instructions in our
        // own README, would process the same logical event once per attempt.

        return mongoTemplate.updateFirst(stillMine, update, Job.class).getMatchedCount() > 0;
    }

    @Override
    public boolean failIfOwned(String jobId, String workerId, int attempt, Integer statusCode, String error) {
        Instant now = Instant.now();

        Query stillMine = new Query(
                Criteria.where("id").is(jobId)
                        .and("lockedBy").is(workerId)
                        .and("lockExpiresAt").gt(now)
        );

        Update update = new Update()
                .set("status", JobStatus.FAILED)
                .set("attempt", attempt)
                .set("lastRunAt", now)
                .set("lastStatusCode", statusCode)
                .set("lastError", error)
                .set("completedAt", now)
                .set("updatedAt", now)
                .set("lockedBy", null)
                .set("lockExpiresAt", null);

        return mongoTemplate.updateFirst(stillMine, update, Job.class).getMatchedCount() > 0;
    }

    @Override
    public boolean rollForwardIfOwned(String jobId, String workerId, Instant nextRunAt,
                                      Instant lastRunAt, Integer statusCode) {
        Instant now = Instant.now();

        Query stillMine = new Query(
                Criteria.where("id").is(jobId)
                        .and("lockedBy").is(workerId)
                        .and("lockExpiresAt").gt(now)
        );

        Update update = new Update()
                .set("status", JobStatus.PENDING)
                .set("nextRunAt", nextRunAt)
                // Unlike a retry, this DOES move — a rollover is a new firing, and it must get a
                // new identity or every occurrence of a daily job would share one idempotency key
                // and the receiver would deduplicate all but the first away, forever.
                .set("currentRunScheduledFor", nextRunAt)
                .set("attempt", 0)
                .set("claimCount", 0)
                .set("lastRunAt", lastRunAt)
                .set("lastStatusCode", statusCode)
                .set("lastError", null)
                .set("updatedAt", now)
                .set("lockedBy", null)
                .set("lockExpiresAt", null);

        return mongoTemplate.updateFirst(stillMine, update, Job.class).getMatchedCount() > 0;
    }

    @Override
    public boolean releaseIfOwned(String jobId, String workerId) {
        Instant now = Instant.now();

        Query stillMine = new Query(
                Criteria.where("id").is(jobId)
                        .and("lockedBy").is(workerId)
        );

        Update update = new Update()
                .set("status", JobStatus.PENDING)
                .set("lockedBy", null)
                .set("lockExpiresAt", null)
                .set("updatedAt", now);

        return mongoTemplate.updateFirst(stillMine, update, Job.class).getMatchedCount() > 0;
    }

    @Override
    public long releaseAllOwnedBy(String workerId) {
        Instant now = Instant.now();

        Query mine = new Query(
                Criteria.where("lockedBy").is(workerId)
                        .and("status").is(JobStatus.CLAIMED)
        );

        Update update = new Update()
                .set("status", JobStatus.PENDING)
                .set("lockedBy", null)
                .set("lockExpiresAt", null)
                .set("updatedAt", now);

        return mongoTemplate.updateMulti(mine, update, Job.class).getModifiedCount();
    }

    @Override
    public long reclaimExpiredLeases(Duration backoff) {
        Instant now = Instant.now();

        Query expired = new Query(
                Criteria.where("status").is(JobStatus.CLAIMED)
                        .and("lockExpiresAt").lt(now)
        );

        Update update = new Update()
                .set("status", JobStatus.PENDING)
                .set("lockedBy", null)
                .set("lockExpiresAt", null)
                // Pushing nextRunAt forward is not cosmetic. Left in the past, the job is due the
                // moment it returns to PENDING and gets re-claimed on the next 200ms poll — so a
                // job whose worker hung on an unresponsive endpoint would be retried against that
                // same endpoint five times a second, for as long as it stays down.
                .set("nextRunAt", now.plus(backoff))
                .set("updatedAt", now);

        return mongoTemplate.updateMulti(expired, update, Job.class).getModifiedCount();
    }

    @Override
    public long quarantinePoisonJobs(int maxClaims, String reason) {
        Instant now = Instant.now();

        Query poison = new Query(
                Criteria.where("status").is(JobStatus.CLAIMED)
                        .and("lockExpiresAt").lt(now)
                        .and("claimCount").gte(maxClaims)
        );

        Update update = new Update()
                .set("status", JobStatus.FAILED)
                .set("lastError", reason)
                .set("completedAt", now)
                .set("updatedAt", now)
                .set("lockedBy", null)
                .set("lockExpiresAt", null);

        return mongoTemplate.updateMulti(poison, update, Job.class).getModifiedCount();
    }

    @Override
    public long countDue(Instant now) {
        Query query = new Query(
                Criteria.where("status").is(JobStatus.PENDING)
                        .and("nextRunAt").lte(now)
        );
        return mongoTemplate.count(query, Job.class);
    }
}
