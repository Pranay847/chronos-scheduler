package dev.pranay.chronos.repository;

import dev.pranay.chronos.domain.JobStatus;

import java.time.Instant;

/**
 * The terminal state to write for a finished firing.
 *
 * <p>A value object rather than a Spring Data {@code Update} so the scheduler package never has to
 * import persistence types to say what happened.
 *
 * @param status       terminal status to set
 * @param attempt      delivery attempts made for this firing, persisted alongside the outcome
 * @param lastRunAt    when the delivery ran
 * @param statusCode   the receiver's response code, or null if we never got one
 */
public record JobCompletion(
        JobStatus status,
        int attempt,
        Instant lastRunAt,
        Integer statusCode
) {

    public static JobCompletion succeeded(int attempt, Instant lastRunAt, Integer statusCode) {
        return new JobCompletion(JobStatus.SUCCEEDED, attempt, lastRunAt, statusCode);
    }

    public static JobCompletion failed(int attempt, Instant lastRunAt, Integer statusCode) {
        return new JobCompletion(JobStatus.FAILED, attempt, lastRunAt, statusCode);
    }
}
