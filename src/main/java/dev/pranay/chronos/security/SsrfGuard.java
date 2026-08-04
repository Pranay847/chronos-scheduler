package dev.pranay.chronos.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.pranay.chronos.config.SecurityProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * Stops the service being used as a proxy into networks it can reach and the caller cannot.
 *
 * <p>This service takes a URL from a user and makes an HTTP request to it. That is textbook
 * server-side request forgery, and the classic exploit is a job targeting
 * {@code http://169.254.169.254/latest/meta-data/iam/security-credentials/} — the cloud metadata
 * endpoint — which would hand the attacker our IAM credentials via a webhook they control.
 *
 * <h2>Why the {@code InetAddress.isX()} helpers are not enough</h2>
 *
 * <p>The obvious implementation chains {@code isLoopbackAddress()}, {@code isLinkLocalAddress()},
 * {@code isSiteLocalAddress()}, {@code isAnyLocalAddress()} and {@code isMulticastAddress()}. It
 * looks exhaustive and it catches the headline attack. It also has holes that are easy to walk
 * through:
 *
 * <ul>
 *   <li><b>{@code fc00::/7}</b> — IPv6 unique local addresses. {@code isSiteLocalAddress()} tests
 *       for the <em>deprecated</em> {@code fec0::/10} prefix, so {@code fd00::1} passes every one
 *       of those five checks. On an IPv6-enabled Docker network, that is your own service mesh.</li>
 *   <li><b>{@code 100.64.0.0/10}</b> — carrier-grade NAT. Not "site local" by any Java definition,
 *       but routes to internal infrastructure at several cloud providers.</li>
 *   <li><b>{@code 0.0.0.0/8}</b> — {@code isAnyLocalAddress()} matches <em>only</em> the exact
 *       address {@code 0.0.0.0}. On Linux, {@code 0.1.2.3} is a perfectly good way to reach
 *       localhost.</li>
 * </ul>
 *
 * <p>So the ranges are enumerated explicitly below, where they can be read and checked against
 * RFC 6890 rather than trusted to a helper whose semantics are subtler than its name.
 *
 * <h2>The rebinding window this does not close</h2>
 *
 * <p>Validation happens at creation <em>and</em> at delivery, because DNS can change in between —
 * that is a DNS rebinding attack. But even the delivery-time check leaves a race: after we resolve
 * and approve an address, {@code HttpClient} performs its <em>own</em> resolution before
 * connecting, and nothing binds the address we validated to the one it dials. Java's
 * {@code HttpClient} exposes no connection-time hook to close that gap.
 *
 * <p>Closing it properly means resolving once, validating, then connecting to the pinned IP with an
 * explicit {@code Host} header and SNI configured so TLS still validates — genuinely fiddly, and
 * deferred. What is <em>not</em> deferred is being straight about it: the window is narrowed by
 * checking twice and by a short DNS cache TTL, and it is documented rather than papered over.
 * "Here is the window that remains and here is what I would do with more time" is a better answer
 * than a guard that quietly does less than it appears to.
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    /**
     * Address ranges a webhook may never target.
     *
     * <p>From RFC 6890 and friends. Ordered roughly by how likely each is to be the one someone
     * actually tries.
     */
    private static final List<CidrBlock> DENIED = List.of(
            // The attack this section exists for: AWS, Azure and GCP instance metadata.
            CidrBlock.of("169.254.0.0/16", "link-local / cloud instance metadata"),

            CidrBlock.of("127.0.0.0/8", "IPv4 loopback"),
            // NOT just 0.0.0.0 — the whole /8 routes to localhost on Linux.
            CidrBlock.of("0.0.0.0/8", "\"this host on this network\""),
            CidrBlock.of("10.0.0.0/8", "RFC 1918 private"),
            CidrBlock.of("172.16.0.0/12", "RFC 1918 private"),
            CidrBlock.of("192.168.0.0/16", "RFC 1918 private"),
            // Missed entirely by isSiteLocalAddress().
            CidrBlock.of("100.64.0.0/10", "carrier-grade NAT"),
            CidrBlock.of("192.0.0.0/24", "IETF protocol assignments"),
            CidrBlock.of("192.0.2.0/24", "TEST-NET-1"),
            CidrBlock.of("198.18.0.0/15", "benchmarking"),
            CidrBlock.of("198.51.100.0/24", "TEST-NET-2"),
            CidrBlock.of("203.0.113.0/24", "TEST-NET-3"),
            CidrBlock.of("224.0.0.0/4", "multicast"),
            CidrBlock.of("240.0.0.0/4", "reserved"),

            CidrBlock.of("::1/128", "IPv6 loopback"),
            CidrBlock.of("::/128", "IPv6 unspecified"),
            // The gap isSiteLocalAddress() leaves wide open.
            CidrBlock.of("fc00::/7", "IPv6 unique local (ULA)"),
            CidrBlock.of("fe80::/10", "IPv6 link-local"),
            CidrBlock.of("ff00::/8", "IPv6 multicast"),
            // Both can encode an IPv4 address inside a public-looking IPv6 one.
            CidrBlock.of("64:ff9b::/96", "NAT64"),
            CidrBlock.of("2002::/16", "6to4")
    );

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final SecurityProperties properties;

    public SsrfGuard(SecurityProperties properties) {
        this.properties = properties;
        if (properties.allowPrivateTargets()) {
            // Loud on purpose. This switch turns a webhook scheduler into an open proxy into
            // whatever network it runs in, and the only thing standing between that being a test
            // convenience and being an incident is somebody noticing the config.
            log.warn("SSRF PROTECTION DISABLED (chronos.security.allow-private-targets=true). "
                    + "Private, loopback and link-local targets are permitted. "
                    + "This must never be set in a deployment reachable by untrusted callers.");
        }
    }

    /**
     * Validates a target URL and every address behind it.
     *
     * @throws InvalidTargetException if the URL is malformed, unresolvable, or resolves to any
     *                                denied range
     */
    public void validate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidTargetException("target.url is not a valid URI: " + e.getReason());
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidTargetException(
                    "target.url must use http or https, got '" + scheme + "'");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidTargetException("target.url has no host");
        }

        InetAddress[] addresses;
        try {
            // getAllByName, not getByName. getByName returns only the FIRST address, so a host
            // with one public and one private A record passes validation on the public one while
            // the connection may well use the other. Every address has to clear the list.
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // A user typo is a 400, not a 500.
            throw new InvalidTargetException("target.url host '" + host + "' does not resolve");
        }

        if (properties.allowPrivateTargets()) {
            return;
        }

        for (InetAddress address : addresses) {
            for (CidrBlock block : DENIED) {
                if (block.contains(address)) {
                    log.warn("Blocked SSRF attempt: host {} resolves to {} ({})",
                            host, address.getHostAddress(), block.description());
                    throw new InvalidTargetException(
                            "target.url resolves to a forbidden address range (%s): %s"
                                    .formatted(block.description(), address.getHostAddress()));
                }
            }
        }
    }

    /** Exposed for tests, so each range can be asserted individually rather than in bulk. */
    public boolean isDenied(InetAddress address) {
        return DENIED.stream().anyMatch(block -> block.contains(address));
    }
}
