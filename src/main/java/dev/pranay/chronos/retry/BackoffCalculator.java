package dev.pranay.chronos.retry;

import dev.pranay.chronos.domain.RetryPolicy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with equal jitter.
 *
 * <p>Jitter is not decoration. Without it, every job that failed against a downstream outage
 * retries on exactly the same schedule, so the moment that service recovers it takes the entire
 * backlog as one synchronised burst — and often goes straight back down. Equal jitter (half the
 * interval fixed, half random) spreads the retries while keeping a guaranteed minimum wait.
 */
@Component
public class BackoffCalculator {

    /**
     * Upper bound on the shift.
     *
     * <p>{@code baseMs << 40} is already ~10^15 ms, far past any sane {@code backoffMaxMs}, and it
     * leaves 23 bits of headroom in a long. The point is to clamp somewhere well below 63, not to
     * pick a meaningful number.
     */
    private static final int MAX_SHIFT = 32;

    /**
     * Delay before attempt {@code attempt + 1}, in milliseconds.
     *
     * <h2>Why this is not the obvious one-liner</h2>
     *
     * <p>The natural implementation is {@code baseMs * (1L << (attempt - 1))}, and it is broken at
     * both ends of the range in ways that produce a <em>number</em> rather than an error:
     *
     * <ul>
     *   <li><b>{@code attempt = 0}</b> — Java masks long shift distances to 6 bits, so
     *       {@code 1L << -1} is not 0, it is {@code 1L << 63} = {@code Long.MIN_VALUE}.
     *       {@code Math.min} keeps the negative, half of it is negative, and
     *       {@code ThreadLocalRandom.nextLong(bound)} throws on a non-positive bound. Whether
     *       {@code attempt} can be 0 here depends on call ordering elsewhere, which is exactly why
     *       it should be clamped rather than assumed.</li>
     *   <li><b>large {@code attempt}</b> — the multiply overflows <em>before</em> the cap is
     *       applied, and {@code Math.min} can only cap a value that still means something. A
     *       negative result means {@code nextRunAt} lands in the past, so the job is due
     *       immediately, forever: a hot retry loop that pins a worker and reads from outside like
     *       an unexplained throughput collapse.</li>
     * </ul>
     *
     * <p>Clamping the shift first makes both impossible, and costs one {@code Math.min}.
     */
    public Duration compute(int attempt, RetryPolicy policy) {
        int shift = Math.max(0, Math.min(attempt - 1, MAX_SHIFT));
        long exponential = policy.backoffBaseMs() << shift;

        // The Math.max guards the theoretical case of a pathological backoffBaseMs; after the
        // shift clamp it should be unreachable, which is the intent.
        long capped = Math.min(Math.max(exponential, 0L), policy.backoffMaxMs());

        long half = capped / 2;
        long jittered = half + ThreadLocalRandom.current().nextLong(half + 1);
        return Duration.ofMillis(jittered);
    }

    /**
     * Backoff for a receiver that told us when to come back.
     *
     * <p>A {@code Retry-After} is a real signal and worth honouring, but it is also attacker- and
     * bug-controlled, so it is clamped to the policy's ceiling. Otherwise a receiver returning
     * {@code Retry-After: 86400} — broken or hostile — parks the job in your collection for a day.
     */
    public Duration computeWithRetryAfter(int attempt, RetryPolicy policy, Duration retryAfter) {
        if (retryAfter == null || retryAfter.isNegative()) {
            return compute(attempt, policy);
        }
        long requested = retryAfter.toMillis();
        long allowed = Math.min(requested, policy.backoffMaxMs());
        return Duration.ofMillis(Math.max(allowed, 0L));
    }
}
