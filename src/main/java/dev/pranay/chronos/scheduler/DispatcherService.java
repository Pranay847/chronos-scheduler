package dev.pranay.chronos.scheduler;

import dev.pranay.chronos.delivery.DeliveryResult;
import dev.pranay.chronos.delivery.HttpDeliveryClient;
import dev.pranay.chronos.domain.DeadLetter;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobExecution;
import dev.pranay.chronos.repository.DeadLetterRepository;
import dev.pranay.chronos.repository.JobCompletion;
import dev.pranay.chronos.repository.JobRepository;
import dev.pranay.chronos.repository.TenantRepository;
import dev.pranay.chronos.retry.BackoffCalculator;
import dev.pranay.chronos.retry.RetryDecider;
import dev.pranay.chronos.retry.RetryDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Delivers one claimed job and records what happened.
 *
 * <p>The ordering in {@link #dispatch} is deliberate and is the part worth reading.
 */
@Service
public class DispatcherService {

    private static final Logger log = LoggerFactory.getLogger(DispatcherService.class);

    private final HttpDeliveryClient deliveryClient;
    private final JobRepository jobRepository;
    private final MongoTemplate executionMongoTemplate;
    private final SchedulerMetrics metrics;
    private final WorkerIdentity worker;
    private final RetryDecider retryDecider;
    private final BackoffCalculator backoffCalculator;
    private final DeadLetterRepository deadLetterRepository;
    private final CronCalculator cronCalculator;
    private final TenantRepository tenantRepository;

    public DispatcherService(HttpDeliveryClient deliveryClient,
                             JobRepository jobRepository,
                             @Qualifier("executionMongoTemplate") MongoTemplate executionMongoTemplate,
                             SchedulerMetrics metrics,
                             WorkerIdentity worker,
                             RetryDecider retryDecider,
                             BackoffCalculator backoffCalculator,
                             DeadLetterRepository deadLetterRepository,
                             CronCalculator cronCalculator,
                             TenantRepository tenantRepository) {
        this.deliveryClient = deliveryClient;
        this.jobRepository = jobRepository;
        this.executionMongoTemplate = executionMongoTemplate;
        this.metrics = metrics;
        this.worker = worker;
        this.retryDecider = retryDecider;
        this.backoffCalculator = backoffCalculator;
        this.deadLetterRepository = deadLetterRepository;
        this.cronCalculator = cronCalculator;
        this.tenantRepository = tenantRepository;
    }

    /** Dispatches as this JVM's worker. The normal path. */
    public void dispatch(Job job) {
        dispatch(job, worker.id());
    }

    /**
     * Dispatches on behalf of a named worker.
     *
     * <p>The worker id is a parameter rather than always the injected singleton because it has to
     * match the id the job was <em>claimed</em> under — {@link #dispatch} completes the job with a
     * write conditional on exactly that. Taking it explicitly is what lets the concurrency test
     * run ten distinct workers inside one JVM; hardcoding the singleton would make every simulated
     * worker fail its own ownership check, which would look like a bug in the locking rather than
     * in the test.
     */
    public void dispatch(Job job, String workerId) {
        // A delivery is genuinely about to happen, so this is where `attempt` moves — not at
        // claim time. Claiming is not attempting: a worker that crashes before reaching this
        // line, or a job bounced by an open circuit breaker in Phase 5, has consumed a pickup
        // (claimCount) but none of the customer's retry budget.
        job.setAttempt(job.getAttempt() + 1);

        Instant startedAt = Instant.now();
        // Signed with the owning tenant's primary secret. A job whose tenant has vanished is
        // delivered unsigned rather than not at all — the receiver's signature check will reject
        // it, which is the correct place for that decision to be made.
        String signingSecret = tenantRepository.findById(job.getTenantId())
                .map(t -> t.primarySecret())
                .orElse(null);

        DeliveryResult result = deliveryClient.deliver(job, signingSecret);
        Instant completedAt = Instant.now();

        // Re-assert the lease before writing anything. If it is gone, this delivery was a
        // duplicate that another worker has already superseded — at-least-once means the
        // duplicate request itself is acceptable, but a duplicate *write* is not: it would
        // produce two execution records for one firing and, once cron exists, roll the schedule
        // forward twice and silently drop a firing.
        boolean stillOurs = result.succeeded()
                ? recordSuccess(job, workerId, startedAt, result)
                : recordFailure(job, workerId, result);

        if (!stillOurs) {
            metrics.recordLeaseLost();
            log.warn("Worker {} lost the lease on job {} mid-delivery after {}ms; discarding result ({})",
                    workerId, job.getId(), result.durationMs(), result.outcome());
            return;
        }

        // Only now is this delivery officially ours to count. Recording metrics before the
        // ownership check would inflate every delivery counter with work that was thrown away.
        long driftMs = Duration.between(job.getCurrentRunScheduledFor(), startedAt).toMillis();
        metrics.recordDrift(driftMs);
        metrics.recordDelivery(result.outcome(), result.durationMs());

        JobExecution execution = JobExecution.record(job, workerId, startedAt, completedAt,
                result.outcome(), result.statusCode(), result.bodySnippet(), result.errorMessage());
        executionMongoTemplate.insert(execution);

        log.debug("Job {} {} in {}ms (drift {}ms, attempt {}, key {})",
                job.getId(), result.outcome(), result.durationMs(), driftMs,
                job.getAttempt(), job.idempotencyKey());
    }

    /**
     * A delivery succeeded. Either the job is done, or it rolls forward to its next occurrence.
     *
     * <p>The branch is what makes a cron job recurring: a one-time job reaches a terminal state,
     * while a recurring one goes straight back to PENDING with the next scheduled time. There is
     * no separate "cron loop" anywhere in the system — recurrence is just a job that keeps
     * re-arming itself, so it inherits claiming, leasing and crash recovery unchanged.
     */
    private boolean recordSuccess(Job job, String workerId, Instant startedAt, DeliveryResult result) {
        if (!job.getSchedule().isCron()) {
            return jobRepository.completeIfOwned(job.getId(), workerId,
                    JobCompletion.succeeded(job.getAttempt(), startedAt, result.statusCode()));
        }

        // Rolled forward from the SCHEDULED time, not from startedAt. Using the actual run time
        // lets retry delay drag the whole series forward and silently swallow slots — an every-5
        // minutes job whose 09:05 run finally succeeds at 09:06 would compute 09:10 and drop 09:05
        // with nothing recording that it vanished.
        Instant nextRunAt = cronCalculator.nextExecution(
                job.getSchedule(), job.getCurrentRunScheduledFor(), Instant.now());

        boolean written = jobRepository.rollForwardIfOwned(
                job.getId(), workerId, nextRunAt, startedAt, result.statusCode());

        if (written) {
            log.debug("Cron job {} fired for {}; next occurrence {}",
                    job.getId(), job.getCurrentRunScheduledFor(), nextRunAt);
        }
        return written;
    }

    /**
     * A delivery failed. Decide whether it deserves another go, and write the corresponding state.
     *
     * <p>Retries are deliberately <em>not</em> a special execution path. A retry is just a job
     * back in {@code PENDING} with a later {@code nextRunAt}, which the existing claim machinery
     * picks up like any other due work. That is what keeps retry behaviour identical across
     * workers and survives a worker dying mid-backoff — the alternative, holding the job in memory
     * and sleeping, loses every pending retry when the process does.
     */
    private boolean recordFailure(Job job, String workerId, DeliveryResult result) {
        RetryDecision decision = retryDecider.decide(result);
        boolean budgetLeft = job.getAttempt() < job.getRetryPolicy().maxAttempts();

        if (decision.retry() && budgetLeft) {
            Duration backoff = backoffCalculator.computeWithRetryAfter(
                    job.getAttempt(), job.getRetryPolicy(), decision.retryAfter());
            Instant nextRunAt = Instant.now().plus(backoff);

            boolean written = jobRepository.rescheduleIfOwned(job.getId(), workerId, nextRunAt,
                    job.getAttempt(), result.statusCode(), decision.reason());

            if (written) {
                metrics.recordRetryScheduled();
                log.info("Job {} attempt {}/{} failed ({}); retrying in {}ms",
                        job.getId(), job.getAttempt(), job.getRetryPolicy().maxAttempts(),
                        decision.reason(), backoff.toMillis());
            }
            return written;
        }

        String reason = decision.retry()
                ? "Exhausted %d attempts; last failure: %s".formatted(job.getAttempt(), decision.reason())
                : decision.reason();

        // Snapshot before failing the job, so the dead letter records the job as it died rather
        // than as it looks afterwards.
        boolean written = jobRepository.failIfOwned(job.getId(), workerId, job.getAttempt(),
                result.statusCode(), reason);

        if (written) {
            job.setLastError(reason);
            job.setLastStatusCode(result.statusCode());
            deadLetterRepository.save(DeadLetter.from(job, reason));
            metrics.recordDeadLettered();
            log.warn("Job {} dead-lettered after {} attempt(s): {}", job.getId(), job.getAttempt(), reason);
        }
        return written;
    }
}
