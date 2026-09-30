package dev.pranay.chronos.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing and prefix-boundary cases for {@link CidrBlock}. The partial-byte and cross-family cases
 * live in {@link SsrfGuardTest}, next to the deny list they protect.
 */
class CidrBlockTest {

    @Test
    void rejectsAStringWithNoPrefix() {
        assertThatThrownBy(() -> CidrBlock.of("10.0.0.0", "no slash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a CIDR");
    }

    /** The upper bound depends on the family: 32 bits is fine for IPv4 only. */
    @Test
    void rejectsAPrefixLongerThanTheAddress() {
        assertThatThrownBy(() -> CidrBlock.of("10.0.0.0/33", "too long"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prefix out of range");
        assertThatThrownBy(() -> CidrBlock.of("::/129", "too long"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrBlock.of("10.0.0.0/-1", "negative"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroPrefixMatchesEveryAddressOfItsFamily() throws UnknownHostException {
        CidrBlock all = CidrBlock.of("0.0.0.0/0", "everything");

        assertThat(all.contains(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(all.contains(InetAddress.getByName("255.255.255.255"))).isTrue();
        assertThat(all.contains(InetAddress.getByName("2001:db8::1"))).isFalse();
    }

    @Test
    void fullLengthPrefixMatchesExactlyOneAddress() throws UnknownHostException {
        CidrBlock loopback = CidrBlock.of("::1/128", "IPv6 loopback");

        assertThat(loopback.contains(InetAddress.getByName("::1"))).isTrue();
        assertThat(loopback.contains(InetAddress.getByName("::2"))).isFalse();
    }
}
