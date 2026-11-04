package dev.pranay.chronos.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token-bucket behaviour of {@link RateLimiter}: burst capacity, per-tenant isolation, continuous
 * refill and idle eviction.
 */
class RateLimiterTest {

    @Test
    void allowsAFullBurstUpToTheLimitThenRefuses() {
        RateLimiter limiter = new RateLimiter();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("tenant-a", 5)).as("permit %d", i + 1).isTrue();
        }
        assertThat(limiter.tryConsume("tenant-a", 5)).isFalse();
    }

    @Test
    void oneTenantExhaustingItsBucketDoesNotAffectAnother() {
        RateLimiter limiter = new RateLimiter();

        assertThat(limiter.tryConsume("noisy", 1)).isTrue();
        assertThat(limiter.tryConsume("noisy", 1)).isFalse();

        assertThat(limiter.tryConsume("quiet", 1)).isTrue();
        assertThat(limiter.trackedTenants()).isEqualTo(2);
    }

    /** 60,000/minute is 1,000 tokens a second, so a 50 ms pause refills well over one token. */
    @Test
    void refillsContinuouslyRatherThanOnAWindowBoundary() throws InterruptedException {
        RateLimiter limiter = new RateLimiter();
        int perMinute = 60_000;

        while (limiter.tryConsume("tenant-a", perMinute)) {
            // drain the bucket
        }
        Thread.sleep(50);

        assertThat(limiter.tryConsume("tenant-a", perMinute)).isTrue();
    }

    @Test
    void evictsOnlyBucketsIdleForAtLeastAnHour() {
        RateLimiter limiter = new RateLimiter();
        limiter.tryConsume("tenant-a", 10);

        limiter.evictIdle(Instant.now().plus(Duration.ofMinutes(59)));
        assertThat(limiter.trackedTenants()).isEqualTo(1);

        limiter.evictIdle(Instant.now().plus(Duration.ofMinutes(61)));
        assertThat(limiter.trackedTenants()).isZero();
    }

    /** An evicted tenant comes back with a full bucket, not the exhausted one it left behind. */
    @Test
    void anEvictedTenantStartsAgainWithAFullBucket() {
        RateLimiter limiter = new RateLimiter();
        assertThat(limiter.tryConsume("tenant-a", 1)).isTrue();
        assertThat(limiter.tryConsume("tenant-a", 1)).isFalse();

        limiter.evictIdle(Instant.now().plus(Duration.ofHours(2)));

        assertThat(limiter.tryConsume("tenant-a", 1)).isTrue();
    }
}
