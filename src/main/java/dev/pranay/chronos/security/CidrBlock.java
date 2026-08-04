package dev.pranay.chronos.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * A single CIDR range, for membership testing.
 *
 * <p>Written out rather than pulled in because the whole point of the SSRF guard is that the
 * ranges are <em>explicit and auditable</em> — a reviewer should be able to read the list and check
 * it against RFC 6890 without following a dependency.
 *
 * @param network      the network address, as raw bytes (4 for IPv4, 16 for IPv6)
 * @param prefixLength number of leading bits that must match
 * @param description  what this range is, for the rejection message
 */
public record CidrBlock(byte[] network, int prefixLength, String description) {

    public static CidrBlock of(String cidr, String description) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Not a CIDR: " + cidr);
        }
        String address = cidr.substring(0, slash);
        int prefix = Integer.parseInt(cidr.substring(slash + 1));

        try {
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (prefix < 0 || prefix > bytes.length * 8) {
                throw new IllegalArgumentException("Prefix out of range for " + cidr);
            }
            return new CidrBlock(bytes, prefix, description);
        } catch (UnknownHostException e) {
            // A literal IP never resolves, so this is a malformed constant rather than a lookup.
            throw new IllegalArgumentException("Malformed CIDR: " + cidr, e);
        }
    }

    /**
     * Whether {@code candidate} falls inside this range.
     *
     * <p>Compares whole bytes first, then the leftover bits with a mask. Address families must
     * match: an IPv4 address is never inside an IPv6 block, and vice versa. Java hands back
     * IPv4-mapped addresses ({@code ::ffff:127.0.0.1}) as 4-byte {@code Inet4Address}, so those are
     * caught by the IPv4 ranges rather than needing their own case.
     */
    public boolean contains(InetAddress candidate) {
        byte[] bytes = candidate.getAddress();
        if (bytes.length != network.length) {
            return false;
        }

        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;

        if (!Arrays.equals(bytes, 0, fullBytes, network, 0, fullBytes)) {
            return false;
        }
        if (remainingBits == 0) {
            return true;
        }

        int mask = (0xFF00 >> remainingBits) & 0xFF;
        return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
