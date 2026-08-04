package dev.pranay.chronos.domain;

/**
 * Lifecycle of a job document.
 *
 * <p>Only {@link #PENDING} is claimable. The poller's claim query (§3) filters on
 * {@code status = PENDING AND nextRunAt <= now}, which is why {@code status} is the
 * equality prefix of the {@code idx_claim} compound index.
 */
public enum JobStatus {

    /** Claimable. Waiting for {@code nextRunAt} to arrive. */
    PENDING,

    /** Owned by a worker until {@code lockExpiresAt}. Reclaimed by the reaper if that passes. */
    CLAIMED,

    /** Terminal, one-time jobs only. Cron jobs return to PENDING with a new nextRunAt. */
    SUCCEEDED,

    /** Terminal. Retries exhausted, or a non-retryable response. Has a dead_letters entry. */
    FAILED,

    /** Terminal. Cancelled via DELETE /v1/jobs/{id}. */
    CANCELLED,

    /** Temporarily excluded from claiming. Resumable. */
    PAUSED
}
