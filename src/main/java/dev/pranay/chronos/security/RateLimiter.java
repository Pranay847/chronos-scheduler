package dev.pranay.chronos.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant token bucket over job creation.
 *
 * <p>Hand-rolled rather than Bucket4j: it is forty lines, has no version-compatibility surface, and
 * the interesting part of rate limiting in a system like this is not the algorithm anyway — it is
 * the scoping, discussed below.
 *
 * <p>A bucket refills continuously rather than resetting on a boundary. Fixed windows let a caller
 * spend a full quota at 11:59:59 and another at 12:00:00, so the actual burst is twice the
 * configured limit at exactly the moment a boundary passes.
 *
 * <h2>This is per worker, and that is a real caveat</h2>
 *
 * <p>Each worker keeps its own buckets, so a tenant limited to 1,000/minute can in practice do
 * 1,000 per worker per minute — three workers, 3,000. Making it global means a round-trip to shared
 * state on every job creation, on the hot path, to defend a limit that is a courtesy rather than a
 * correctness boundary.
 *
 * <p>The honest framing: this stops accidental runaway loops and casual abuse, which is what it is
 * for. It is not a billing control, and if it needed to become one the implementation would move to
 * Redis or a Mongo counter. Same reasoning as the per-worker circuit breakers — worth saying
 * plainly rather than letting a reviewer discover it.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Consumes one token.
     *
     * @return true if the caller is within its limit
     */
    public boolean tryConsume(String tenantId, int permitsPerMinute) {
        Bucket bucket = buckets.computeIfAbsent(tenantId, id -> new Bucket(permitsPerMinute));
        return bucket.tryConsume(permitsPerMinute);
    }

    /** Drops buckets untouched for an hour, so a churn of tenant ids cannot grow the map forever. */
    public void evictIdle(Instant now) {
        buckets.entrySet().removeIf(entry ->
                Duration.between(entry.getValue().lastAccess, now).toHours() >= 1);
    }

    int trackedTenants() {
        return buckets.size();
    }

    private static final class Bucket {

        private double tokens;
        private Instant lastRefill = Instant.now();
        private Instant lastAccess = Instant.now();

        private Bucket(int capacity) {
            this.tokens = capacity;
        }

        private synchronized boolean tryConsume(int permitsPerMinute) {
            Instant now = Instant.now();
            lastAccess = now;

            double elapsedSeconds = Duration.between(lastRefill, now).toNanos() / 1_000_000_000.0;
            tokens = Math.min(permitsPerMinute, tokens + elapsedSeconds * (permitsPerMinute / 60.0));
            lastRefill = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
