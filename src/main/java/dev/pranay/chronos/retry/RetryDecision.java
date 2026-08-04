package dev.pranay.chronos.retry;

import java.time.Duration;

/**
 * Whether a failed delivery gets another go.
 *
 * @param retry      true to reschedule, false to dead-letter immediately
 * @param retryAfter honoured delay the receiver asked for, or null to use exponential backoff
 * @param reason     human-readable explanation, persisted on the job and the dead letter
 */
public record RetryDecision(boolean retry, Duration retryAfter, String reason) {

    public static RetryDecision retryNow(String reason) {
        return new RetryDecision(true, null, reason);
    }

    public static RetryDecision retryAfter(Duration delay, String reason) {
        return new RetryDecision(true, delay, reason);
    }

    public static RetryDecision giveUp(String reason) {
        return new RetryDecision(false, null, reason);
    }
}
