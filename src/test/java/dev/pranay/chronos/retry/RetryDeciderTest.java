package dev.pranay.chronos.retry;

import dev.pranay.chronos.delivery.DeliveryResult;
import dev.pranay.chronos.domain.ExecutionOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class RetryDeciderTest {

    private final RetryDecider decider = new RetryDecider();

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 429, 408})
    void transientServerFailuresAreRetried(int status) {
        assertThat(decider.decide(failedWith(status, null)).retry()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 405, 409, 410, 413, 422})
    void genuineClientErrorsGoStraightToTheDeadLetterQueue(int status) {
        RetryDecision decision = decider.decide(failedWith(status, null));
        assertThat(decision.retry())
                .as("status %d will produce the same answer every time; retrying just wastes both sides", status)
                .isFalse();
    }

    /**
     * Auth failures are retried despite being 4xx.
     *
     * <p>They look permanent and usually aren't: the common cause is a customer rotating
     * credentials on their receiving endpoint, which resolves itself in minutes. Treating them as
     * fatal dead-letters a burst of perfectly good jobs every time someone rotates a secret.
     */
    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void authAndNotFoundAreRetriedAsLikelyTransient(int status) {
        assertThat(decider.decide(failedWith(status, null)).retry()).isTrue();
    }

    /**
     * A timeout is retried precisely because we do not know what happened.
     *
     * <p>The request may have been processed and the response lost. That ambiguity is unresolvable
     * over an unreliable network, and it is the exact case the stable idempotency key exists to
     * make safe — retry, and let the receiver deduplicate.
     */
    @Test
    void timeoutsAndTransportErrorsAreRetried() {
        assertThat(decider.decide(outcome(ExecutionOutcome.TIMEOUT)).retry()).isTrue();
        assertThat(decider.decide(outcome(ExecutionOutcome.ERROR)).retry()).isTrue();
    }

    @Test
    void retryAfterInSecondsIsParsed() {
        RetryDecision decision = decider.decide(failedWith(503, "120"));
        assertThat(decision.retry()).isTrue();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(120));
    }

    /**
     * The HTTP-date form of {@code Retry-After} is parsed too.
     *
     * <p>RFC 9110 allows both delay-seconds and an HTTP-date, and both appear in the wild.
     * Handling only the integer form means silently ignoring every receiver that sends a date —
     * no error, no log, just backoff that quietly disregards what was asked for. The plan called
     * this "a two-line change"; it is not, and this test is why.
     */
    @Test
    void retryAfterAsAnHttpDateIsParsed() {
        String httpDate = ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .plusMinutes(5)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);

        Duration parsed = decider.parseRetryAfter(httpDate);

        assertThat(parsed).isNotNull();
        assertThat(parsed.toMinutes()).isBetween(4L, 5L);
    }

    @Test
    void aRetryAfterDateInThePastBecomesZeroRatherThanNegative() {
        String past = ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .minusHours(1)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);

        assertThat(decider.parseRetryAfter(past)).isEqualTo(Duration.ZERO);
    }

    @Test
    void unparseableRetryAfterIsIgnoredRatherThanFatal() {
        assertThat(decider.parseRetryAfter("soon-ish")).isNull();
        assertThat(decider.parseRetryAfter("")).isNull();
        assertThat(decider.parseRetryAfter(null)).isNull();

        // and the decision still stands on its own
        assertThat(decider.decide(failedWith(503, "soon-ish")).retry()).isTrue();
    }

    @Test
    void successNeedsNoRetry() {
        assertThat(decider.decide(outcome(ExecutionOutcome.SUCCEEDED)).retry()).isFalse();
    }

    private static DeliveryResult failedWith(int status, String retryAfter) {
        return new DeliveryResult(ExecutionOutcome.FAILED, status, "body", null, retryAfter, 12L);
    }

    private static DeliveryResult outcome(ExecutionOutcome outcome) {
        return new DeliveryResult(outcome, null, null, "boom", null, 12L);
    }
}
