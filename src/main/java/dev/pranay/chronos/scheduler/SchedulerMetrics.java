package dev.pranay.chronos.scheduler;

import dev.pranay.chronos.domain.ExecutionOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The measurement harness.
 *
 * <p>Deliberately built in Phase 2 rather than Phase 8. Every headline number this project claims
 * comes from here, and a harness built at the end can only describe the system you ended up with —
 * built now, it tells you in week two whether the polling design actually holds, and every later
 * phase gets a before/after signal for free.
 *
 * <h2>Why drift is a histogram and not a set of percentiles</h2>
 *
 * <p>The tempting call is {@code publishPercentiles(0.5, 0.95, 0.99)}. It is wrong for this system,
 * and wrong in a way that produces a number rather than an error.
 *
 * <p>That option makes each worker compute its own quantiles and export them as gauges. Quantiles
 * do not add up: the mean of three workers' p99 is not the fleet p99, and neither is the max.
 * Prometheus will graph something, and that something is meaningless. The failure is specific to
 * succeeding at the thing this project is about — running N identical workers — so it would not
 * appear in single-worker testing and would quietly invalidate the one metric the resume leans on.
 *
 * <p>{@code publishPercentileHistogram()} exports cumulative {@code le} buckets instead. Buckets
 * are additive, so {@code histogram_quantile(0.99, sum(rate(scheduler_drift_seconds_bucket[5m])) by (le))}
 * is a true fleet-wide p99 across any number of workers.
 *
 * <p>Note the exported names are in <em>seconds</em>, not milliseconds: Micrometer publishes Timers
 * in base units and appends {@code _seconds}. Naming the meter {@code scheduler.drift.ms} would
 * export {@code scheduler_drift_ms_seconds}, which is both ugly and a lie.
 */
@Component
public class SchedulerMetrics {

    private final Timer drift;
    private final Timer deliveryDuration;
    private final Counter leaseLost;
    private final Counter claimContention;
    private final Counter leasesReclaimed;
    private final Counter poisonQuarantined;
    private final Counter retriesScheduled;
    private final Counter deadLettered;
    private final Counter earlyWakeups;
    private final MeterRegistry registry;

    public SchedulerMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.drift = Timer.builder("scheduler.drift")
                .description("Delay between when a job was scheduled to fire and when its delivery began")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5))
                .register(registry);

        this.deliveryDuration = Timer.builder("scheduler.delivery.duration")
                .description("Time spent inside the receiver's endpoint")
                .publishPercentileHistogram()
                .register(registry);

        this.leaseLost = Counter.builder("scheduler.lease.lost")
                .description("Deliveries whose result was discarded because the worker no longer held the lease")
                .register(registry);

        this.claimContention = Counter.builder("scheduler.claim.contention")
                .description("Poll cycles that claimed nothing while work was due")
                .register(registry);

        this.leasesReclaimed = Counter.builder("scheduler.leases.reclaimed")
                .description("Expired leases taken back from workers presumed dead or stalled")
                .register(registry);

        this.poisonQuarantined = Counter.builder("scheduler.jobs.quarantined")
                .description("Jobs failed for exceeding the claim cap without ever completing")
                .register(registry);

        this.retriesScheduled = Counter.builder("scheduler.retries")
                .description("Failed deliveries rescheduled for another attempt")
                .register(registry);

        this.deadLettered = Counter.builder("scheduler.deadletters")
                .description("Jobs that exhausted their retries or hit a non-retryable response")
                .register(registry);

        this.earlyWakeups = Counter.builder("scheduler.wakeup.early")
                .description("Jobs claimed by a change-stream wakeup rather than by the poll cycle")
                .register(registry);

        // Pre-register one delivery counter per outcome, at zero.
        //
        // Micrometer creates a tagged counter lazily, on first increment, so an outcome that has
        // not happened yet is simply ABSENT from the scrape — not zero. A fresh deployment
        // therefore has no scheduler_delivery_total{outcome="FAILED"} series at all, a Grafana
        // panel for it reads "No data" instead of a flat line, and worse, an alert on
        // rate(...) of a series that does not exist never fires. The quietest possible failure is
        // the one where nothing has gone wrong yet.
        for (ExecutionOutcome outcome : ExecutionOutcome.values()) {
            deliveryCounter(outcome);
        }
    }

    private Counter deliveryCounter(ExecutionOutcome outcome) {
        return Counter.builder("scheduler.delivery")
                .description("Delivery attempts by outcome")
                .tag("outcome", outcome.name())
                .register(registry);
    }

    /** Records how late a firing was. Negative values are clamped — a job must never fire early. */
    public void recordDrift(long driftMs) {
        drift.record(Math.max(0, driftMs), TimeUnit.MILLISECONDS);
    }

    public void recordDelivery(ExecutionOutcome outcome, long durationMs) {
        deliveryDuration.record(durationMs, TimeUnit.MILLISECONDS);
        deliveryCounter(outcome).increment();
    }

    /**
     * A worker finished a delivery it no longer owned and threw the result away.
     *
     * <p>Worth an alert rather than just a graph. A non-zero rate means the lease is shorter than
     * real queue wait plus delivery time, which is the condition that produces duplicate
     * deliveries. It is the empirical half of the lease sizing that {@code LeaseSanityCheck}
     * cannot check at startup.
     */
    public void recordLeaseLost() {
        leaseLost.increment();
    }

    public void recordClaimContention() {
        claimContention.increment();
    }

    /**
     * Leases taken back from workers that stopped responding.
     *
     * <p>The single best health signal this service produces. A steady trickle is normal — it is
     * how crash recovery is supposed to look. A spike means workers are dying or so overloaded
     * they cannot finish inside a lease, and either way it precedes duplicate deliveries, because
     * every reclaim of a job that was still alive produces one.
     */
    public void recordLeasesReclaimed(long count) {
        leasesReclaimed.increment(count);
    }

    /**
     * Jobs failed for being picked up too many times without ever completing.
     *
     * <p>Should be zero. Anything else means a job is killing the workers that touch it, and the
     * counter is what stops it walking through the whole fleet unnoticed.
     */
    public void recordPoisonQuarantined(long count) {
        poisonQuarantined.increment(count);
    }

    /**
     * Jobs claimed because a change-stream wakeup fired at their scheduled instant.
     *
     * <p>The number that says whether the optimisation is doing anything. Near zero with jobs still
     * firing means the stream is dead or filtered wrong and everything has quietly fallen back to
     * the poll cycle — which is a latency regression with no error attached to it, and therefore
     * exactly the kind of thing that needs a metric rather than a log line.
     */
    public void recordEarlyWakeup(long count) {
        earlyWakeups.increment(count);
    }

    /** A failed delivery that earned another attempt. */
    public void recordRetryScheduled() {
        retriesScheduled.increment();
    }

    /**
     * A job that gave up.
     *
     * <p>Worth graphing next to {@code scheduler.retries}: retries rising while dead letters stay
     * flat is a downstream having a bad hour and recovering. Both rising together means the
     * retries are not helping and something is systematically wrong with either the requests or
     * the destination.
     */
    public void recordDeadLettered() {
        deadLettered.increment();
    }
}
