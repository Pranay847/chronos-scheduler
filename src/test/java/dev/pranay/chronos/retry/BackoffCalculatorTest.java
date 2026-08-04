package dev.pranay.chronos.retry;

import dev.pranay.chronos.domain.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Backoff arithmetic.
 *
 * <p>Pure functions, no Spring, no container — these run in milliseconds and cover the one part of
 * the retry path where a bug produces a plausible-looking number rather than an exception.
 */
class BackoffCalculatorTest {

    private final BackoffCalculator calculator = new BackoffCalculator();
    private final RetryPolicy policy = new RetryPolicy(5, 1_000L, 300_000L);

    /**
     * The regression test for the overflow bug.
     *
     * <p>The natural implementation, {@code baseMs * (1L << (attempt - 1))}, fails at both ends.
     * At {@code attempt = 0} Java masks the shift distance to 6 bits, so {@code 1L << -1} is
     * {@code Long.MIN_VALUE} rather than 0, and {@code ThreadLocalRandom.nextLong} then throws on
     * the negative bound. At large attempts the multiply overflows before {@code Math.min} can cap
     * it, yielding a negative delay — a job permanently due, re-claimed on every poll, forever.
     *
     * <p>Neither is reachable through normal use, which is exactly why it needs a test that walks
     * the boundaries deliberately: 32 is the clamp, 63 and 64 are where shift masking wraps.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 10, 31, 32, 33, 62, 63, 64, 100, Integer.MAX_VALUE})
    void backoffIsAlwaysWithinBoundsAndNeverThrows(int attempt) {
        assertThatCode(() -> {
            Duration backoff = calculator.compute(attempt, policy);
            assertThat(backoff).isNotNull();
            assertThat(backoff.toMillis())
                    .as("attempt %d produced an out-of-range delay", attempt)
                    .isBetween(0L, policy.backoffMaxMs());
        }).doesNotThrowAnyException();
    }

    @Test
    void backoffGrowsWithAttemptUntilItReachesTheCap() {
        // Equal jitter means each result is a range, so compare the guaranteed minimum (half the
        // capped interval) rather than a single sample.
        long first = minOverSamples(1);
        long second = minOverSamples(2);
        long third = minOverSamples(3);

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);

        // 1000 << 9 = 512s, well past the 300s cap.
        assertThat(calculator.compute(10, policy).toMillis()).isLessThanOrEqualTo(policy.backoffMaxMs());
    }

    /**
     * Equal jitter: at least half the interval, never more than all of it.
     *
     * <p>The lower bound is the point. Pure random jitter can return ~0 and hammer a downstream
     * that is still recovering; equal jitter keeps a guaranteed minimum wait while still spreading
     * a synchronised backlog.
     */
    @Test
    void jitterStaysWithinTheEqualJitterBand() {
        long expectedFull = 1_000L << 2; // attempt 3 -> base << 2 = 4000ms
        for (int i = 0; i < 200; i++) {
            long ms = calculator.compute(3, policy).toMillis();
            assertThat(ms).isBetween(expectedFull / 2, expectedFull);
        }
    }

    @Test
    void jitterActuallyVaries() {
        // A fixed backoff would let a whole backlog retry in lockstep and re-flatten a downstream
        // the moment it recovers, so "it varies" is a real requirement rather than a nicety.
        long distinct = java.util.stream.IntStream.range(0, 100)
                .mapToLong(i -> calculator.compute(4, policy).toMillis())
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(1);
    }

    @Test
    void retryAfterIsHonouredWhenTheReceiverAsksForOne() {
        Duration backoff = calculator.computeWithRetryAfter(1, policy, Duration.ofSeconds(42));
        assertThat(backoff).isEqualTo(Duration.ofSeconds(42));
    }

    /**
     * A receiver cannot park a job indefinitely.
     *
     * <p>{@code Retry-After} is attacker- and bug-controlled input. Honouring it unconditionally
     * means a broken or hostile endpoint returning {@code Retry-After: 86400} takes its jobs out
     * of circulation for a day.
     */
    @Test
    void retryAfterIsCappedAtThePolicyMaximum() {
        Duration backoff = calculator.computeWithRetryAfter(1, policy, Duration.ofDays(1));
        assertThat(backoff.toMillis()).isEqualTo(policy.backoffMaxMs());
    }

    @Test
    void aMissingOrNegativeRetryAfterFallsBackToExponentialBackoff() {
        assertThat(calculator.computeWithRetryAfter(2, policy, null).toMillis())
                .isBetween(0L, policy.backoffMaxMs());
        assertThat(calculator.computeWithRetryAfter(2, policy, Duration.ofSeconds(-5)).toMillis())
                .isBetween(0L, policy.backoffMaxMs());
    }

    private long minOverSamples(int attempt) {
        return java.util.stream.IntStream.range(0, 50)
                .mapToLong(i -> calculator.compute(attempt, policy).toMillis())
                .min()
                .orElseThrow();
    }
}
