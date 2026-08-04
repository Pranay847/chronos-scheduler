package dev.pranay.chronos.delivery;

import dev.pranay.chronos.domain.ExecutionOutcome;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.Target;
import dev.pranay.chronos.security.HmacSigner;
import dev.pranay.chronos.security.InvalidTargetException;
import dev.pranay.chronos.security.SsrfGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Sends the webhook.
 *
 * <p>Deliberately boring and deliberately bounded. Every call has an explicit timeout, redirects
 * are refused, and the response body is truncated on the way in.
 */
@Component
public class HttpDeliveryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDeliveryClient.class);

    /**
     * Headers a caller may not set on their own job.
     *
     * <p>Two groups, for two different reasons. {@code X-Idempotency-Key} and the (Phase 6)
     * signature headers are ours — letting a caller set them would let them shadow the values the
     * receiver verifies against. The rest are restricted by {@code java.net.http}, which throws
     * {@code IllegalArgumentException} rather than ignoring them, so an unfiltered user header
     * turns into an unhandled 500 that looks like a bug in the dispatcher.
     */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "host", "content-length", "connection", "upgrade", "expect",
            "transfer-encoding", "x-idempotency-key", "x-webhook-signature", "x-webhook-timestamp");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SsrfGuard ssrfGuard;
    private final HmacSigner hmacSigner;

    public HttpDeliveryClient(ObjectMapper objectMapper, SsrfGuard ssrfGuard, HmacSigner hmacSigner) {
        this.objectMapper = objectMapper;
        this.ssrfGuard = ssrfGuard;
        this.hmacSigner = hmacSigner;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Redirects are refused rather than followed. A 302 is a second URL that never
                // passed the SSRF validation the first one did, which makes "follow redirects"
                // an open door straight through that guard (Phase 6).
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DeliveryResult deliver(Job job) {
        return deliver(job, null);
    }

    /**
     * Delivers, signing with {@code signingSecret} when one is supplied.
     *
     * @param signingSecret the tenant's primary secret, or null for an unsigned delivery
     */
    public DeliveryResult deliver(Job job, String signingSecret) {
        Target target = job.getTarget();
        long start = System.nanoTime();

        try {
            // Re-validate immediately before connecting. The URL passed this same check at
            // creation time, but DNS can change in between — that gap is a rebinding attack, and
            // a single check at creation would be trivially defeated by a record that points
            // somewhere harmless for exactly as long as it takes to be accepted.
            ssrfGuard.validate(target.url());

            // Serialize ONCE. The signature has to cover the bytes actually transmitted, so this
            // array is both signed and sent — serializing separately for each would let two
            // Jackson passes disagree on key order and produce signatures that verify for some
            // payloads and not others.
            byte[] body = objectMapper.writeValueAsBytes(target.payload());

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(target.url()))
                    .timeout(Duration.ofMillis(target.timeoutMs()))
                    .header("Content-Type", "application/json")
                    // Stable across every retry of this firing — derived from the scheduled time,
                    // not the poll time. This is the string the receiver deduplicates on, and it
                    // is the entire reason at-least-once delivery is safe to build on.
                    .header("X-Idempotency-Key", job.idempotencyKey())
                    .method(target.method(), HttpRequest.BodyPublishers.ofByteArray(body));

            if (signingSecret != null) {
                Instant signedAt = Instant.now();
                builder.header("X-Webhook-Timestamp", String.valueOf(signedAt.getEpochSecond()));
                builder.header("X-Webhook-Signature", hmacSigner.sign(body, signedAt, signingSecret));
            }

            for (Map.Entry<String, String> header : target.headers().entrySet()) {
                if (!RESERVED_HEADERS.contains(header.getKey().toLowerCase())) {
                    builder.header(header.getKey(), header.getValue());
                }
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long durationMs = elapsedMs(start);

            // Carried through unparsed. Retry-After has two legal forms (delay-seconds and
            // HTTP-date) and deciding what to do with it is the retry layer's business, not the
            // transport's.
            String retryAfter = response.headers().firstValue("retry-after").orElse(null);

            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return new DeliveryResult(
                    ok ? ExecutionOutcome.SUCCEEDED : ExecutionOutcome.FAILED,
                    response.statusCode(),
                    DeliveryResult.sanitise(response.body()),
                    null,
                    retryAfter,
                    durationMs);

        } catch (InvalidTargetException e) {
            // Blocked between creation and delivery — a DNS record that now points somewhere it
            // must not. Not retryable: it is a security decision, not a transient failure.
            log.warn("Blocked delivery for job {}: {}", job.getId(), e.getMessage());
            return new DeliveryResult(ExecutionOutcome.ERROR, null, null,
                    "Blocked by SSRF guard: " + e.getMessage(), null, elapsedMs(start));

        } catch (HttpTimeoutException e) {
            // Distinct from FAILED on purpose: a timeout means we do not know whether the
            // receiver processed the request. That uncertainty is exactly what the idempotency
            // key is for, and it is worth being able to count separately.
            return new DeliveryResult(ExecutionOutcome.TIMEOUT, null, null,
                    "Timed out after " + target.timeoutMs() + "ms", null, elapsedMs(start));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(ExecutionOutcome.ERROR, null, null,
                    "Interrupted during delivery", null, elapsedMs(start));

        } catch (Exception e) {
            log.debug("Delivery to {} failed", target.url(), e);
            return new DeliveryResult(ExecutionOutcome.ERROR, null, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null, elapsedMs(start));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
