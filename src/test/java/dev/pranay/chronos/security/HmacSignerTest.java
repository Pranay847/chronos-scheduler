package dev.pranay.chronos.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    private final HmacSigner signer = new HmacSigner();
    private static final String SECRET = "whsec_test_secret";
    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

    @Test
    void signatureIsStableForTheSameInputs() {
        byte[] body = "{\"userId\":42}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign(body, NOW, SECRET))
                .isEqualTo(signer.sign(body, NOW, SECRET))
                .startsWith("v1=");
    }

    /**
     * The timestamp is inside the signed material, not merely alongside it.
     *
     * <p>If it were only a header, anyone who captured one request could replay it forever with a
     * fresh timestamp and a still-valid signature. Binding it means tampering with the timestamp
     * invalidates the signature.
     */
    @Test
    void changingTheTimestampChangesTheSignature() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign(body, NOW, SECRET))
                .isNotEqualTo(signer.sign(body, NOW.plusSeconds(1), SECRET));
    }

    @Test
    void changingTheBodyOrSecretChangesTheSignature() {
        byte[] a = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] b = "{\"n\":2}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign(a, NOW, SECRET)).isNotEqualTo(signer.sign(b, NOW, SECRET));
        assertThat(signer.sign(a, NOW, SECRET)).isNotEqualTo(signer.sign(a, NOW, "different-secret"));
    }

    /**
     * Byte-for-byte, not semantically.
     *
     * <p>Two JSON documents that mean the same thing but differ in key order produce different
     * signatures — which is why the delivery client serializes once and sends the array it signed.
     * Serializing separately for signing and sending would fail exactly here, intermittently,
     * depending on the payload.
     */
    @Test
    void signatureCoversExactBytesNotEquivalentJson() {
        byte[] ordered = "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] reordered = "{\"b\":2,\"a\":1}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign(ordered, NOW, SECRET))
                .as("semantically identical JSON, different bytes, different signature")
                .isNotEqualTo(signer.sign(reordered, NOW, SECRET));
    }

    // ---------------------------------------------------------------- verification

    @Test
    void aFreshValidSignatureVerifies() {
        byte[] body = "{\"event\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        String signature = signer.sign(body, NOW, SECRET);

        assertThat(signer.verify(body, String.valueOf(NOW.getEpochSecond()), signature,
                SECRET, NOW, HmacSigner.DEFAULT_TOLERANCE)).isTrue();
    }

    /**
     * A valid signature on an old request is still a replay.
     *
     * <p>The signature never expires on its own — only the timestamp check makes a captured
     * request stop working, which is why verification has to test both.
     */
    @Test
    void aValidSignatureOutsideTheToleranceWindowIsRejected() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Instant longAgo = NOW.minus(Duration.ofHours(2));
        String signature = signer.sign(body, longAgo, SECRET);

        assertThat(signer.verify(body, String.valueOf(longAgo.getEpochSecond()), signature,
                SECRET, NOW, HmacSigner.DEFAULT_TOLERANCE))
                .as("two hours old — correctly signed and still a replay")
                .isFalse();
    }

    /** A far-future timestamp is as suspect as a stale one, so tolerance is checked both ways. */
    @Test
    void aFarFutureTimestampIsRejected() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Instant future = NOW.plus(Duration.ofHours(2));
        String signature = signer.sign(body, future, SECRET);

        assertThat(signer.verify(body, String.valueOf(future.getEpochSecond()), signature,
                SECRET, NOW, HmacSigner.DEFAULT_TOLERANCE)).isFalse();
    }

    @Test
    void aTamperedBodyFailsVerification() {
        byte[] original = "{\"amount\":10}".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "{\"amount\":99}".getBytes(StandardCharsets.UTF_8);
        String signature = signer.sign(original, NOW, SECRET);

        assertThat(signer.verify(tampered, String.valueOf(NOW.getEpochSecond()), signature,
                SECRET, NOW, HmacSigner.DEFAULT_TOLERANCE)).isFalse();
    }

    @Test
    void theWrongSecretFailsVerification() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = signer.sign(body, NOW, SECRET);

        assertThat(signer.verify(body, String.valueOf(NOW.getEpochSecond()), signature,
                "attacker-guess", NOW, HmacSigner.DEFAULT_TOLERANCE)).isFalse();
    }

    @Test
    void malformedHeadersAreRejectedRatherThanThrowing() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String valid = signer.sign(body, NOW, SECRET);

        assertThat(signer.verify(body, null, valid, SECRET, NOW, HmacSigner.DEFAULT_TOLERANCE)).isFalse();
        assertThat(signer.verify(body, "not-a-number", valid, SECRET, NOW, HmacSigner.DEFAULT_TOLERANCE)).isFalse();
        assertThat(signer.verify(body, String.valueOf(NOW.getEpochSecond()), null,
                SECRET, NOW, HmacSigner.DEFAULT_TOLERANCE)).isFalse();
    }

    @Test
    void aSignatureWithoutItsVersionPrefixIsRejected() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String unprefixed = signer.sign(body, NOW, SECRET).substring("v1=".length());

        assertThat(signer.verify(body, String.valueOf(NOW.getEpochSecond()), unprefixed,
                SECRET, NOW, HmacSigner.DEFAULT_TOLERANCE))
                .as("the version tag is part of the contract, not decoration")
                .isFalse();
    }
}
