package dev.pranay.chronos.delivery;

import dev.pranay.chronos.domain.ExecutionOutcome;

/**
 * Outcome of one HTTP attempt.
 *
 * @param outcome          what happened
 * @param statusCode       receiver's response code, or null if we never got one
 * @param bodySnippet      truncated, sanitised response body — see {@link #MAX_SNIPPET}
 * @param errorMessage     exception detail when no response arrived
 * @param retryAfterHeader raw {@code Retry-After} value, kept unparsed because it has two legal
 *                         formats and interpreting it is the retry layer's job, not the
 *                         transport's
 * @param durationMs       wall time spent in the call
 */
public record DeliveryResult(
        ExecutionOutcome outcome,
        Integer statusCode,
        String bodySnippet,
        String errorMessage,
        String retryAfterHeader,
        long durationMs
) {

    /**
     * Response bodies are third-party bytes that get persisted and later echoed back through our
     * own API, so they are truncated and stripped of control characters before they are stored.
     */
    public static final int MAX_SNIPPET = 512;

    public boolean succeeded() {
        return outcome == ExecutionOutcome.SUCCEEDED;
    }

    public static String sanitise(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String trimmed = body.length() > MAX_SNIPPET ? body.substring(0, MAX_SNIPPET) : body;
        return trimmed.replaceAll("\\p{Cntrl}", " ").strip();
    }
}
