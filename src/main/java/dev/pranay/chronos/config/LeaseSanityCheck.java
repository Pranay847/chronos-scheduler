package dev.pranay.chronos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with a lease that cannot outlive a delivery.
 *
 * <h2>Why this isn't just "lease &gt; 2 × timeout"</h2>
 *
 * <p>That rule is the obvious one and it is not sufficient, because the lease starts running at
 * <em>claim</em> time while the HTTP call starts at <em>dispatch</em> time. In between, the job
 * sits in the dispatcher queue. Claim a batch of 50 and the last one may wait a long time before
 * anything happens to it, burning lease the whole while. The real bound is:
 *
 * <pre>
 *   lease &gt; queueWait(p99) + connectTimeout + requestTimeout + reaperPeriod + clockSkew
 * </pre>
 *
 * <p>Only the timeout term is known at startup, so that is all this check can enforce — it catches
 * the configuration that is obviously broken, not the one that is subtly under-provisioned. The
 * queue-wait term is empirical, which is why {@code scheduler.lease.lost} exists: a non-zero rate
 * means real queue wait has outgrown the lease, and that metric is the only honest way to know.
 *
 * <p>The failure this prevents is nasty precisely because it is invisible at low load. A lease
 * shorter than a delivery means the reaper hands the job to a second worker while the first is
 * still talking to the receiver — a duplicate delivery, and without the conditional write-back in
 * {@code JobRepositoryCustomImpl}, a corrupted job state to go with it.
 */
@Component
public class LeaseSanityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LeaseSanityCheck.class);

    /** Headroom over the raw timeout: enough for queue wait, the reaper's period, and clock skew. */
    private static final int MINIMUM_MULTIPLE = 3;

    private final ChronosProperties properties;

    public LeaseSanityCheck(ChronosProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        long lease = properties.leaseDurationMs();
        long timeout = properties.defaultTimeoutMs();
        long floor = timeout * MINIMUM_MULTIPLE;

        if (lease < floor) {
            throw new IllegalStateException(
                    ("Lease duration %dms is too short for a %dms delivery timeout. A lease must outlive "
                     + "queue wait + the request itself + the reaper period, or the reaper will reclaim jobs "
                     + "that are still in flight and deliver them twice. Set chronos.lease-duration-ms to at "
                     + "least %dms, or lower chronos.default-timeout-ms.")
                            .formatted(lease, timeout, floor));
        }

        log.info("Lease {}ms vs delivery timeout {}ms (floor {}ms) — headroom {}x",
                lease, timeout, floor, String.format("%.1f", (double) lease / timeout));
    }
}
