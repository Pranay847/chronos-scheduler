package dev.pranay.chronos.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs outgoing webhooks so a receiver can verify they came from us and are not a replay.
 *
 * <p>Stripe's scheme, because it is well understood and consumers may already have code for it:
 *
 * <pre>
 *   X-Webhook-Timestamp: 1735689600
 *   X-Webhook-Signature: v1=&lt;hex(HMAC_SHA256(secret, timestamp + "." + rawBody))&gt;
 * </pre>
 *
 * <p>The timestamp is inside the signed material, not merely alongside it. Signing the body alone
 * would let anyone who captured one request replay it forever; binding the timestamp means a
 * receiver can reject anything older than its tolerance and know the timestamp itself was not
 * tampered with.
 *
 * <p>The {@code v1=} prefix is a version tag. It costs four characters now and is the difference
 * between rotating to a new algorithm later and breaking every consumer at once.
 */
@Component
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /** How much clock skew a receiver should tolerate. Documented so consumers use the same value. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    /**
     * Signs the exact bytes that will be transmitted.
     *
     * <p><b>The signature must cover the bytes actually sent, not an equivalent rendering of
     * them.</b> The tempting shape is to serialize the payload here for signing and let the HTTP
     * client serialize it again for the wire — and nothing guarantees two Jackson passes emit
     * identical key ordering. The failure that produces is miserable: signatures that verify for
     * most payloads and fail for some, with no pattern a consumer can report usefully.
     *
     * <p>So this takes a {@code byte[]}, and the caller sends that same array.
     */
    public String sign(byte[] body, Instant timestamp, String secret) {
        String prefix = timestamp.getEpochSecond() + ".";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);

        byte[] signedPayload = new byte[prefixBytes.length + body.length];
        System.arraycopy(prefixBytes, 0, signedPayload, 0, prefixBytes.length);
        System.arraycopy(body, 0, signedPayload, prefixBytes.length, body.length);

        return "v1=" + HexFormat.of().formatHex(hmac(signedPayload, secret));
    }

    /**
     * Reference verifier — the receiver's half.
     *
     * <p>This exists to be copied. It is the one piece of code in the project other people will
     * actually run, so the constant-time comparison below is not defensive padding: shipping a
     * verifier that uses {@code equals} would teach every consumer a timing side-channel, and it
     * would be pasted verbatim into production systems that are not ours.
     *
     * <p>Both halves matter. A valid signature on a two-hour-old request is still a replay, so the
     * timestamp is checked too — and checked in both directions, since a far-future timestamp is
     * just as suspect as a stale one.
     */
    public boolean verify(byte[] body, String timestampHeader, String signatureHeader,
                          String secret, Instant now, Duration tolerance) {
        if (timestampHeader == null || signatureHeader == null) {
            return false;
        }

        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }

        Instant sent = Instant.ofEpochSecond(epochSeconds);
        if (Duration.between(sent, now).abs().compareTo(tolerance) > 0) {
            return false;
        }

        String expected = sign(body, sent, secret);

        // MessageDigest.isEqual is constant-time. String.equals short-circuits on the first
        // differing character, which leaks how much of a guessed signature was correct and turns
        // forgery into a few thousand requests instead of 2^256 guesses.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmac(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception e) {
            // Both causes are configuration errors, not runtime conditions: HmacSHA256 is
            // guaranteed present on every JVM, and an empty key means a tenant was created wrong.
            throw new IllegalStateException("Unable to compute HMAC signature", e);
        }
    }
}
