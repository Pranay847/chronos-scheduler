package dev.pranay.chronos.config;

import dev.pranay.chronos.scheduler.WorkerIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metric tagging.
 *
 * <p>Every metric this service emits carries the id of the worker that emitted it. That is what
 * makes the fleet-wide aggregation in the Grafana dashboard meaningful — and what makes it
 * verifiable: with a {@code worker} label you can prove that
 * {@code sum(rate(scheduler_drift_seconds_bucket[5m])) by (le)} really is combining several
 * instances, rather than silently graphing one.
 *
 * <p>It is also the label you group by when one worker is behaving differently from the rest, which
 * is the first question worth asking when drift climbs on a fleet where nothing else changed.
 *
 * <p>Applied as a {@link MeterFilter} rather than added at each call site: a tag that has to be
 * remembered is a tag that will eventually be forgotten on exactly the meter you need it on.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterFilter workerIdentityTag(WorkerIdentity worker) {
        return MeterFilter.commonTags(java.util.List.of(
                io.micrometer.core.instrument.Tag.of("worker", worker.id())));
    }

    /**
     * Publishes histogram buckets for delivery duration as well as drift.
     *
     * <p>Same reasoning as {@code SchedulerMetrics}: percentiles computed per instance cannot be
     * combined across workers. Anything that will be read as a fleet-wide percentile has to be
     * exported as buckets, so this is applied to every timer rather than left to each one to
     * remember.
     */
    @Bean
    public MeterFilter schedulerHistograms() {
        return new MeterFilter() {
            @Override
            public io.micrometer.core.instrument.distribution.DistributionStatisticConfig configure(
                    io.micrometer.core.instrument.Meter.Id id,
                    io.micrometer.core.instrument.distribution.DistributionStatisticConfig config) {

                if (id.getName().startsWith("scheduler.")) {
                    return io.micrometer.core.instrument.distribution.DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

}
