package dev.pranay.chronos.api.dto;

import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobStatus;
import dev.pranay.chronos.domain.RetryPolicy;
import dev.pranay.chronos.domain.Schedule;
import dev.pranay.chronos.domain.Target;

import java.time.Instant;

/**
 * Read model for a job.
 *
 * <p>Deliberately not the entity. {@code lockedBy} and {@code lockExpiresAt} are internal
 * coordination state that would only invite clients to depend on it, and {@code version} is a
 * storage detail.
 *
 * <p>Both time fields <em>are</em> exposed, because the difference between them is observable
 * behaviour a caller may need to reason about: {@code nextRunAt} moves as retries are scheduled,
 * while {@code currentRunScheduledFor} identifies the firing those retries belong to and matches
 * the {@code X-Idempotency-Key} their receiver will see.
 */
public record JobResponse(
        String id,
        String tenantId,
        String name,
        Schedule schedule,
        Target target,
        RetryPolicy retryPolicy,
        JobStatus status,
        Instant nextRunAt,
        Instant currentRunScheduledFor,
        int attempt,
        int claimCount,
        Instant lastRunAt,
        Integer lastStatusCode,
        String lastError,
        String triggeredFrom,
        Instant createdAt,
        Instant updatedAt
) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getTenantId(),
                job.getName(),
                job.getSchedule(),
                job.getTarget(),
                job.getRetryPolicy(),
                job.getStatus(),
                job.getNextRunAt(),
                job.getCurrentRunScheduledFor(),
                job.getAttempt(),
                job.getClaimCount(),
                job.getLastRunAt(),
                job.getLastStatusCode(),
                job.getLastError(),
                job.getTriggeredFrom(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
