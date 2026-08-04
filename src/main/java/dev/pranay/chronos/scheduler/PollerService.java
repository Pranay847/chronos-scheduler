package dev.pranay.chronos.scheduler;

import dev.pranay.chronos.config.ChronosProperties;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.repository.JobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Claims due work and hands it to the dispatcher.
 *
 * <p>Runs on every worker; there is no leader and no membership protocol. Coordination is entirely
 * the atomic claim in {@code JobRepositoryCustomImpl}, which is the property that lets you scale by
 * adding containers and reconfiguring nothing.
 *
 * <p>Can be switched off with {@code chronos.poller.enabled=false} — used by tests that drive the
 * claim path by hand rather than race a 200ms loop.
 */
@Component
@ConditionalOnProperty(name = "chronos.poller.enabled", havingValue = "true", matchIfMissing = true)
public class PollerService {

    private static final Logger log = LoggerFactory.getLogger(PollerService.class);

    /** How long shutdown waits for in-flight deliveries before releasing their jobs anyway. */
    private static final long DRAIN_TIMEOUT_MS = 10_000;

    private final JobRepository jobRepository;
    private final DispatcherService dispatcher;
    private final ExecutorService dispatcherExecutor;
    private final SchedulerMetrics metrics;
    private final DueDepthGauge dueDepth;
    private final WorkerIdentity worker;
    private final ChronosProperties properties;

    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean shuttingDown = false;

    public PollerService(JobRepository jobRepository,
                         DispatcherService dispatcher,
                         ExecutorService dispatcherExecutor,
                         SchedulerMetrics metrics,
                         DueDepthGauge dueDepth,
                         WorkerIdentity worker,
                         ChronosProperties properties) {
        this.jobRepository = jobRepository;
        this.dispatcher = dispatcher;
        this.dispatcherExecutor = dispatcherExecutor;
        this.metrics = metrics;
        this.dueDepth = dueDepth;
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${chronos.poll-interval-ms:200}", initialDelayString = "#{@pollJitterMs}")
    public void poll() {
        if (shuttingDown) {
            return;
        }

        MDC.put("workerId", worker.id());
        try {
            int capacity = properties.maxInFlight() - inFlight.get();
            if (capacity <= 0) {
                return;
            }

            int budget = Math.min(properties.claimBatchSize(), capacity);
            int claimed = claimAndDispatch(budget);

            // Claiming nothing is normal when there is nothing to do. Claiming nothing while work
            // is visibly due means every findAndModify lost its race — the signal that contention,
            // not capacity, is the bottleneck. The two have opposite fixes, so it is worth being
            // able to tell them apart: contention wants a smarter claim, capacity wants workers.
            if (claimed == 0 && dueDepth.current() > 0) {
                metrics.recordClaimContention();
            }
        } finally {
            MDC.remove("workerId");
        }
    }

    /**
     * Claims up to {@code budget} due jobs and hands each to the dispatcher.
     *
     * <p>Extracted so the change-stream wakeup can drive the <em>same</em> path rather than
     * reimplementing it. A second claim loop would be a second place for the lease duration, the
     * in-flight cap and the shutdown check to drift out of agreement.
     *
     * @return how many jobs were claimed
     */
    int claimAndDispatch(int budget) {
        int claimed = 0;
        for (int i = 0; i < budget && !shuttingDown; i++) {
            Optional<Job> maybeJob = jobRepository.claimNextDueJob(worker.id(), properties.leaseDuration());
            if (maybeJob.isEmpty()) {
                break;
            }
            claimed++;
            submit(maybeJob.get());
        }
        return claimed;
    }

    /**
     * Claims immediately, outside the poll cycle. Entry point for the change-stream wakeup.
     *
     * <p>Respects the same in-flight ceiling as the scheduled poll: a burst of inserts must not be
     * able to claim more work than the fleet can deliver inside a lease.
     */
    public int wakeAndClaim(int budget) {
        if (shuttingDown) {
            return 0;
        }
        int capacity = properties.maxInFlight() - inFlight.get();
        return capacity <= 0 ? 0 : claimAndDispatch(Math.min(budget, capacity));
    }

    private void submit(Job job) {
        inFlight.incrementAndGet();
        dispatcherExecutor.submit(() -> {
            MDC.put("workerId", worker.id());
            MDC.put("jobId", job.getId());
            try {
                dispatcher.dispatch(job);
            } catch (RuntimeException e) {
                // A throwing dispatch must not leak the counter, or capacity drains away one job
                // at a time until the poller silently stops claiming anything at all.
                log.error("Unhandled failure dispatching job {}", job.getId(), e);
            } finally {
                inFlight.decrementAndGet();
                MDC.clear();
            }
        });
    }

    /**
     * Hands work back on the way out.
     *
     * <p>Without this, every deliberate stop — a rolling deploy, a {@code docker compose down}, a
     * scale-down — leaves claimed jobs stranded for the <em>full lease duration</em>, because from
     * the database's point of view a clean shutdown is indistinguishable from a crash. Thirty
     * seconds of dead air per deploy, for jobs nobody is working on.
     *
     * <p>Three steps, in order. Stop claiming, so the set of held jobs can only shrink. Give
     * in-flight deliveries a bounded chance to finish and write their own results. Then release
     * whatever is still held — those are jobs that were claimed but never got to run, and they can
     * go straight back in the pool.
     *
     * <p>The bound matters: a receiver that hangs must not be able to hold shutdown open forever.
     * Anything still running past the deadline has its job released underneath it, and the
     * conditional write-back in {@code completeIfOwned} is what makes that safe — the late
     * delivery finds it no longer owns the job and discards its result instead of overwriting
     * whoever picked it up next.
     *
     * <p>It also gives the chaos test in Phase 8 a clean contrast: SIGKILL should show duplicates
     * bounded by whatever was in flight, while a graceful stop should show none.
     */
    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("Shutdown: worker {} stopped claiming, draining {} in-flight deliveries",
                worker.id(), inFlight.get());

        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        int stranded = inFlight.get();
        if (stranded > 0) {
            log.warn("Shutdown: {} delivery(ies) still running after {}ms; releasing their jobs anyway",
                    stranded, DRAIN_TIMEOUT_MS);
        }

        long released = jobRepository.releaseAllOwnedBy(worker.id());
        log.info("Shutdown: worker {} released {} unfinished claim(s)", worker.id(), released);
    }

    int inFlightCount() {
        return inFlight.get();
    }
}
