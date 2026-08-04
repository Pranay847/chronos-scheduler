package dev.pranay.chronos.repository;

import dev.pranay.chronos.domain.Job;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Operations that no derived query can express.
 *
 * <p>Every write here is conditional on the caller still owning the job's lease. That is not
 * defensive padding — it is what stops a worker whose lease expired mid-delivery from overwriting
 * the result of the worker that has since taken the job over.
 */
public interface JobRepositoryCustom {

    /**
     * Atomically claims the oldest due job, or returns empty if there is nothing to claim.
     *
     * <p>Safe under concurrency without a transaction or an external lock service, because
     * {@code findAndModify} is atomic at the document level: of two workers racing for the same
     * document, one wins the write and the other's filter no longer matches, so it moves on.
     */
    Optional<Job> claimNextDueJob(String workerId, Duration leaseDuration);

    /**
     * Marks a job terminal, but only if {@code workerId} still holds an unexpired lease on it.
     *
     * @return {@code true} if the write landed; {@code false} if the lease was lost, in which case
     *         the caller must discard its result rather than retry
     */
    boolean completeIfOwned(String jobId, String workerId, JobCompletion completion);

    /**
     * Schedules another attempt at the <em>same firing</em>, if the caller still owns the lease.
     *
     * <p>The critical property is what this does <b>not</b> touch: {@code currentRunScheduledFor}.
     * A retry moves {@code nextRunAt} — that is what schedules it — but the firing it belongs to
     * has not changed, so its identity must not either. Rewriting both together is the natural
     * mistake and it silently gives every attempt its own idempotency key, at which point the
     * receiver sees three unrelated events instead of three copies of one.
     *
     * @param attempt   delivery attempts made so far for this firing
     * @param nextRunAt when to try again
     */
    boolean rescheduleIfOwned(String jobId, String workerId, Instant nextRunAt, int attempt,
                              Integer statusCode, String error);

    /** Marks a job terminally failed after it has been dead-lettered. */
    boolean failIfOwned(String jobId, String workerId, int attempt, Integer statusCode, String error);

    /**
     * Advances a recurring job to its next firing, if the caller still owns the lease.
     *
     * <p>This is the one place {@code currentRunScheduledFor} legitimately moves, and the contrast
     * with {@link #rescheduleIfOwned} is the whole model in two methods. A retry is another attempt
     * at the <em>same</em> firing, so its identity is preserved. A rollover is a genuinely
     * <em>new</em> firing, so it gets a new scheduled time, a new idempotency key, and a fresh
     * attempt budget.
     */
    boolean rollForwardIfOwned(String jobId, String workerId, Instant nextRunAt,
                               Instant lastRunAt, Integer statusCode);

    /** Releases a claim without completing it — used by graceful shutdown. */
    boolean releaseIfOwned(String jobId, String workerId);

    /** Releases every claim held by this worker. The shutdown path. */
    long releaseAllOwnedBy(String workerId);

    /**
     * Returns expired leases to {@code PENDING}, pushed out by {@code backoff}.
     *
     * <p>This is the crash-recovery mechanism: a worker that died holding a claim is
     * indistinguishable from one that is merely slow, so the only safe signal is the lease
     * deadline passing.
     *
     * @return how many jobs were reclaimed
     */
    long reclaimExpiredLeases(Duration backoff);

    /**
     * Fails jobs that have been picked up {@code maxClaims} times without ever completing.
     *
     * <p>Must run <em>before</em> {@link #reclaimExpiredLeases}, or a job that crashes its worker
     * is handed straight back out and never accumulates the evidence against itself.
     *
     * @return how many jobs were quarantined
     */
    long quarantinePoisonJobs(int maxClaims, String reason);

    /** Backs the {@code scheduler.jobs.due.depth} gauge. Called on a schedule, never per scrape. */
    long countDue(Instant now);
}
