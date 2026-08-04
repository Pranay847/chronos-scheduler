package dev.pranay.chronos.security;

import dev.pranay.chronos.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every blocked range, asserted individually.
 *
 * <p>Per-range rather than a handful of representative cases, because the whole finding behind this
 * class is that the ranges people <em>assume</em> are covered are not the ones that actually are.
 * A test that checks 127.0.0.1 and 169.254.169.254 passes just as happily against the naive
 * five-helper implementation that misses IPv6 ULA and CGNAT entirely.
 */
class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard(new SecurityProperties(true, false));

    /**
     * The three ranges the {@code InetAddress.isX()} helpers miss.
     *
     * <p>Called out separately from the bulk list because each one passes all five of
     * {@code isLoopbackAddress}, {@code isLinkLocalAddress}, {@code isSiteLocalAddress},
     * {@code isAnyLocalAddress} and {@code isMulticastAddress}. An implementation built on those
     * helpers looks thorough and lets all three straight through.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "fd00::1",        // IPv6 ULA — isSiteLocalAddress() tests the deprecated fec0::/10
            "fdff:ffff::1",
            "100.64.0.1",     // CGNAT — not "site local" by any Java definition
            "100.127.255.254",
            "0.1.2.3"         // isAnyLocalAddress() matches only the exact address 0.0.0.0
    })
    void blocksTheRangesTheStandardHelpersMiss(String address) throws UnknownHostException {
        InetAddress candidate = InetAddress.getByName(address);

        assertThat(guard.isDenied(candidate))
                .as("%s passes every InetAddress.isX() check and must still be blocked", address)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "169.254.169.254",   // the attack this whole section exists for: cloud metadata
            "169.254.0.1",
            "127.0.0.1",
            "127.1.2.3",
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.0.1",
            "192.168.255.254",
            "0.0.0.0",
            "192.0.0.1",
            "198.18.0.1",
            "224.0.0.1",
            "240.0.0.1",
            "255.255.255.255",
            "::1",
            "fe80::1",
            "ff02::1"
    })
    void blocksEveryPrivateOrReservedRange(String address) throws UnknownHostException {
        assertThat(guard.isDenied(InetAddress.getByName(address)))
                .as("%s must be blocked", address)
                .isTrue();
    }

    /**
     * IPv4-mapped IPv6 does not slip past.
     *
     * <p>{@code ::ffff:127.0.0.1} is a classic bypass attempt against guards that check IPv4 and
     * IPv6 separately. Java normalises these to {@code Inet4Address}, so the IPv4 ranges catch
     * them — asserted rather than assumed, because "the platform happens to handle it" is exactly
     * the kind of belief that turns out to be wrong on a different JDK.
     */
    @ParameterizedTest
    @ValueSource(strings = {"::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.1"})
    void blocksIpv4MappedIpv6Addresses(String address) throws UnknownHostException {
        assertThat(guard.isDenied(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111"})
    void allowsGenuinelyPublicAddresses(String address) throws UnknownHostException {
        assertThat(guard.isDenied(InetAddress.getByName(address)))
                .as("%s is public and must not be blocked — a guard that blocks everything is useless", address)
                .isFalse();
    }

    // ---------------------------------------------------------------- URL-level validation

    @Test
    void rejectsLoopbackTargetsByHostname() {
        assertThatThrownBy(() -> guard.validate("http://localhost:8080/hook"))
                .isInstanceOf(InvalidTargetException.class)
                .hasMessageContaining("forbidden address range");
    }

    @Test
    void rejectsTheCloudMetadataEndpoint() {
        assertThatThrownBy(() -> guard.validate("http://169.254.169.254/latest/meta-data/iam/security-credentials/"))
                .isInstanceOf(InvalidTargetException.class)
                .hasMessageContaining("metadata");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "gopher://evil.example/x", "ftp://example.com/x", "jar:file:///x"})
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(InvalidTargetException.class)
                .hasMessageContaining("http or https");
    }

    @Test
    void rejectsAHostThatDoesNotResolve() {
        assertThatThrownBy(() -> guard.validate("https://this-host-should-not-exist.invalid/hook"))
                .isInstanceOf(InvalidTargetException.class)
                .hasMessageContaining("does not resolve");
    }

    @Test
    void rejectsAUrlWithNoHost() {
        assertThatThrownBy(() -> guard.validate("http:///nohost"))
                .isInstanceOf(InvalidTargetException.class);
    }

    /**
     * The escape hatch works, and is off by default.
     *
     * <p>Worth its own test because the switch exists solely so the rest of the suite can use a
     * loopback receiver — if it silently stopped working, every delivery test would fail at once
     * and the cause would not be obvious.
     */
    @Test
    void theAllowPrivateSwitchBypassesTheDenylist() {
        SsrfGuard permissive = new SsrfGuard(new SecurityProperties(true, true));

        assertThatCode(() -> permissive.validate("http://127.0.0.1:8080/hook")).doesNotThrowAnyException();

        // Scheme validation is not part of the bypass — that check is about what we speak, not
        // where we are pointed.
        assertThatThrownBy(() -> permissive.validate("file:///etc/passwd"))
                .isInstanceOf(InvalidTargetException.class);
    }

    @Test
    void cidrMatchingHandlesPartialByteBoundaries() throws UnknownHostException {
        // 100.64.0.0/10 has a 2-bit remainder in its third-from-top byte, which is exactly where a
        // naive whole-bytes-only implementation goes wrong.
        CidrBlock cgnat = CidrBlock.of("100.64.0.0/10", "CGNAT");

        assertThat(cgnat.contains(InetAddress.getByName("100.64.0.0"))).isTrue();
        assertThat(cgnat.contains(InetAddress.getByName("100.127.255.255"))).isTrue();
        assertThat(cgnat.contains(InetAddress.getByName("100.63.255.255"))).isFalse();
        assertThat(cgnat.contains(InetAddress.getByName("100.128.0.0"))).isFalse();
    }

    @Test
    void anIpv4AddressIsNeverInsideAnIpv6Block() throws UnknownHostException {
        CidrBlock ula = CidrBlock.of("fc00::/7", "ULA");
        assertThat(ula.contains(InetAddress.getByName("10.0.0.1"))).isFalse();
    }
}
