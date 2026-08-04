package dev.pranay.chronos.domain;

/**
 * How one delivery attempt ended.
 *
 * <p>{@link #TIMEOUT} and {@link #ERROR} are split out from {@link #FAILED} rather than folded in,
 * because they answer different questions. A FAILED delivery means the receiver responded and said
 * no — their problem, and the response code says why. A TIMEOUT or ERROR means we never got an
 * answer, so we genuinely do not know whether the receiver processed the request. That distinction
 * is the whole reason the idempotency key exists, and it is worth being able to count the two
 * separately when reading a dashboard.
 */
public enum ExecutionOutcome {

    /** 2xx. */
    SUCCEEDED,

    /** The receiver answered with a non-2xx status. */
    FAILED,

    /** No response within the configured timeout. Delivery may or may not have landed. */
    TIMEOUT,

    /** Connection refused, DNS failure, TLS failure — never reached the receiver. */
    ERROR
}
