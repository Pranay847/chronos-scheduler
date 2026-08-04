package dev.pranay.chronos.domain;

/**
 * Retry budget for a job (§5.2).
 *
 * <p>{@code maxAttempts} counts <em>delivery</em> attempts. It is deliberately not the same
 * as the number of times a job has been picked up — worker crashes and open-circuit-breaker
 * reschedules increment {@code Job.claimCount}, never {@code Job.attempt}. Conflating the two
 * dead-letters jobs whose destination was merely unreachable, without ever having sent them.
 */
public record RetryPolicy(
        int maxAttempts,
        long backoffBaseMs,
        long backoffMaxMs
) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(5, 1_000L, 300_000L);

    public RetryPolicy {
        if (maxAttempts <= 0) {
            maxAttempts = DEFAULT.maxAttempts();
        }
        if (backoffBaseMs <= 0) {
            backoffBaseMs = DEFAULT.backoffBaseMs();
        }
        if (backoffMaxMs <= 0) {
            backoffMaxMs = DEFAULT.backoffMaxMs();
        }
    }
}
