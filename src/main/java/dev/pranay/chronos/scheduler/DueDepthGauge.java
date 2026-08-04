package dev.pranay.chronos.scheduler;

import dev.pranay.chronos.repository.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backlog depth: PENDING jobs whose {@code nextRunAt} has already passed.
 *
 * <p>A rising value is the signal that workers are not keeping up — the number to autoscale on,
 * and the one that explains a drift graph that has started climbing.
 *
 * <h2>Why it is computed on a schedule instead of inside the gauge</h2>
 *
 * <p>The obvious implementation passes the repository method straight to {@code Gauge.builder} and
 * lets Micrometer call it. That evaluates the query <em>on every Prometheus scrape, on every
 * worker</em>. It is a filtered {@code countDocuments} — an index scan, not a counter lookup — so
 * at a 15s scrape across three workers it is twelve index scans a minute against a collection the
 * Phase 8 load test drives into the tens of millions of documents.
 *
 * <p>That cost also scales the wrong way twice over: it grows with the collection, and it grows
 * with the worker count, so it is heaviest exactly when the system is under the most pressure and
 * you most want to trust the number. Computing it on a fixed schedule into an {@code AtomicLong}
 * gives the same freshness at constant scrape cost.
 */
@Component
public class DueDepthGauge {

    private final JobRepository jobRepository;
    private final AtomicLong depth = new AtomicLong();

    public DueDepthGauge(JobRepository jobRepository, MeterRegistry registry) {
        this.jobRepository = jobRepository;
        Gauge.builder("scheduler.jobs.due.depth", depth, AtomicLong::get)
                .description("PENDING jobs whose scheduled time has passed but which no worker has claimed")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${chronos.due-depth-refresh-ms:10000}")
    public void refresh() {
        depth.set(jobRepository.countDue(Instant.now()));
    }

    /**
     * Last computed depth.
     *
     * <p>Read by the poller to tell "nothing to do" apart from "lost every race", without paying
     * for a count query on every 200ms cycle.
     */
    public long current() {
        return depth.get();
    }
}
