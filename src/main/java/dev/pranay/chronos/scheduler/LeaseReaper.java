package dev.pranay.chronos.scheduler;

import dev.pranay.chronos.config.ChronosProperties;
import dev.pranay.chronos.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Crash recovery.
 *
 * <p>A worker that died holding a claim looks exactly like a worker that is merely slow. There is
 * no way to tell them apart from the outside and no point trying: the only safe signal is the lease
 * deadline passing. That is the whole design — no heartbeats to miss, no failure detector to tune,
 * no membership protocol. A lease expires, the work goes back in the pool.
 *
 * <p>Which is also why the accompanying conditional write-back in
 * {@code JobRepositoryCustomImpl#completeIfOwned} is not optional. This class will absolutely take
 * jobs away from workers that are still alive and still working, because it cannot distinguish
 * them from dead ones. Reclaiming a live worker's job is a duplicate <em>delivery</em>, which
 * at-least-once accepts; letting that worker then write its result would be a duplicate
 * <em>write</em>, which nothing accepts.
 */
@Component
@ConditionalOnProperty(name = "chronos.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JobRepository jobRepository;
    private final SchedulerMetrics metrics;
    private final ChronosProperties properties;

    public LeaseReaper(JobRepository jobRepository, SchedulerMetrics metrics, ChronosProperties properties) {
        this.jobRepository = jobRepository;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${chronos.reaper-interval-ms:10000}")
    public void sweep() {
        quarantinePoisonJobs();
        reclaimExpiredLeases();
    }

    /**
     * Fails jobs that keep getting picked up and never finish.
     *
     * <p>Runs first, and the order is the point. A job whose payload kills its worker — an OOM on
     * a huge body, a parser blowup — never fails a <em>delivery</em>, so {@code attempt} never
     * rises and the retry limit in Phase 4 never fires. Reclaim it first and it goes straight back
     * out to kill the next worker, and the next, walking the fleet indefinitely. Checking the
     * claim cap before handing it back is what breaks that loop.
     *
     * <p>Phase 4 routes these to {@code dead_letters} with a replay endpoint. For now they are
     * marked FAILED with an explanation, which is enough to stop the bleeding.
     */
    private void quarantinePoisonJobs() {
        String reason = "Exceeded %d claims without completing — suspected worker-crashing payload"
                .formatted(properties.maxClaims());

        long quarantined = jobRepository.quarantinePoisonJobs(properties.maxClaims(), reason);

        if (quarantined > 0) {
            metrics.recordPoisonQuarantined(quarantined);
            log.error("Quarantined {} job(s) after {} claims without completion — these are killing workers",
                    quarantined, properties.maxClaims());
        }
    }

    private void reclaimExpiredLeases() {
        Duration backoff = Duration.ofMillis(properties.reclaimBackoffMs());
        long reclaimed = jobRepository.reclaimExpiredLeases(backoff);

        if (reclaimed > 0) {
            metrics.recordLeasesReclaimed(reclaimed);
            // WARN rather than INFO on purpose. A steady trickle is crash recovery working as
            // designed; a spike means workers are dying or too overloaded to finish inside a
            // lease, and it is the leading indicator of duplicate deliveries — every reclaim of a
            // job that was actually still alive produces one.
            log.warn("Reclaimed {} expired lease(s); rescheduled {}ms out", reclaimed, backoff.toMillis());
        }
    }
}
