package dev.pranay.chronos.api.dto;

import dev.pranay.chronos.domain.DeadLetter;

import java.time.Instant;

/**
 * Read model for a dead letter.
 *
 * <p>Includes the job snapshot, because the whole point of looking at this list is deciding
 * whether the job is worth replaying, and that decision needs the target and payload.
 */
public record DeadLetterResponse(
        String id,
        String jobId,
        String tenantId,
        JobResponse job,
        Instant failedAt,
        int totalAttempts,
        String lastError,
        Integer lastStatusCode,
        Instant replayedAt,
        String replayJobId
) {

    public static DeadLetterResponse from(DeadLetter letter) {
        return new DeadLetterResponse(
                letter.getId(),
                letter.getJobId(),
                letter.getTenantId(),
                letter.getJobSnapshot() == null ? null : JobResponse.from(letter.getJobSnapshot()),
                letter.getFailedAt(),
                letter.getTotalAttempts(),
                letter.getLastError(),
                letter.getLastStatusCode(),
                letter.getReplayedAt(),
                letter.getReplayJobId());
    }
}
