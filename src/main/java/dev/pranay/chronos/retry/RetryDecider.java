package dev.pranay.chronos.retry;

import dev.pranay.chronos.delivery.DeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Decides whether a failed delivery is worth repeating.
 *
 * <p>The distinction is between "the receiver is having a bad day" and "this request is wrong and
 * will always be wrong". Retrying the first is helpful; retrying the second burns your capacity
 * and theirs to arrive at the same answer five times.
 */
@Component
public class RetryDecider {

    private static final Logger log = LoggerFactory.getLogger(RetryDecider.class);

    public RetryDecision decide(DeliveryResult result) {
        String retryAfterHeader = result.retryAfterHeader();
        return switch (result.outcome()) {
            // Never reached the receiver, or never heard back. We cannot know whether it landed,
            // which is precisely the case the idempotency key exists to make safe to retry.
            case TIMEOUT -> RetryDecision.retryNow("Timed out; delivery may or may not have landed");
            case ERROR -> RetryDecision.retryNow("Transport failure before any response");
            case SUCCEEDED -> RetryDecision.giveUp("Succeeded");
            case FAILED -> classify(result.statusCode(), retryAfterHeader);
        };
    }

    private RetryDecision classify(Integer status, String retryAfterHeader) {
        if (status == null) {
            return RetryDecision.retryNow("Failed with no status code");
        }

        // 5xx — their side is broken, and it may well be transient.
        if (status >= 500) {
            return withRetryAfter(retryAfterHeader, "Server error " + status);
        }

        // 429 and 408 are explicit "try again" signals.
        if (status == 429 || status == 408) {
            return withRetryAfter(retryAfterHeader, "Throttled or timed out upstream (" + status + ")");
        }

        // 401/403 look permanent and mostly aren't. The usual cause is a customer rotating
        // credentials on their receiving endpoint, which resolves itself within minutes — so
        // treating auth failures as fatal dead-letters a burst of perfectly good jobs every time
        // someone rotates a secret. Stripe retries these; so do we.
        if (status == 401 || status == 403) {
            return withRetryAfter(retryAfterHeader, "Auth rejected (" + status + "); likely credential rotation");
        }

        // 404 during a deploy is the same story: the route exists, it just isn't wired up yet.
        if (status == 404) {
            return withRetryAfter(retryAfterHeader, "Endpoint not found (404); may be mid-deploy");
        }

        // Everything else in 4xx is a genuine client error. The request is malformed, or the
        // resource is gone, and sending it again changes nothing.
        if (status >= 400) {
            return RetryDecision.giveUp("Client error " + status + " — retrying cannot help");
        }

        // 1xx/3xx reaching here means redirects are off (they are) and something odd happened.
        return RetryDecision.giveUp("Unexpected status " + status);
    }

    private RetryDecision withRetryAfter(String header, String reason) {
        Duration requested = parseRetryAfter(header);
        return requested == null
                ? RetryDecision.retryNow(reason)
                : RetryDecision.retryAfter(requested, reason + "; honouring Retry-After");
    }

    /**
     * Parses {@code Retry-After}, which RFC 9110 defines in <em>two</em> forms.
     *
     * <p>It is either delay-seconds ({@code Retry-After: 120}) or an HTTP-date
     * ({@code Retry-After: Wed, 21 Oct 2026 07:28:00 GMT}), and both appear in the wild. Handling
     * only the integer form means silently ignoring every receiver that sends a date — no error,
     * no log line, just backoff that quietly disregards what the receiver asked for.
     *
     * <p>The caller clamps the result; this only parses it.
     */
    Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();

        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            // Not the integer form — fall through to the date form.
        }

        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration until = Duration.between(ZonedDateTime.now(when.getZone()), when);
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException e) {
            log.debug("Unparseable Retry-After header: {}", value);
            return null;
        }
    }
}
