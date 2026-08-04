package dev.pranay.chronos.api;

/** Tenant exceeded its job-creation rate. Maps to 429. */
public class RateLimitExceededException extends RuntimeException {

    private final int limitPerMinute;

    public RateLimitExceededException(int limitPerMinute) {
        super("Rate limit of %d job creations per minute exceeded".formatted(limitPerMinute));
        this.limitPerMinute = limitPerMinute;
    }

    public int getLimitPerMinute() {
        return limitPerMinute;
    }
}
