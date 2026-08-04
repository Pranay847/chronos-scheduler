package dev.pranay.chronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tunables, kept out of the code that uses them so the load test in Phase 8 can move them
 * without a rebuild.
 *
 * @param maxPayloadBytes  serialized cap on {@code target.payload}. Mongo's document limit is
 *                         16MB and a dead-lettered job snapshots the entire document, so an
 *                         unbounded payload is charged twice. 256KB is generous for a webhook.
 * @param defaultTimeoutMs per-delivery timeout when the caller doesn't specify one.
 * @param defaultTenantId  placeholder until API-key auth resolves the real tenant in Phase 6.
 * @param pollIntervalMs   how often each worker looks for due work. Pure polling at interval T
 *                         gives mean drift T/2 and worst case T, so 200ms buys sub-second
 *                         precision — which is what webhooks need. Chasing lower costs CPU on
 *                         every worker to buy precision nobody asked for.
 * @param leaseDurationMs  how long a claim is held before the reaper may take it back. See
 *                         {@link LeaseSanityCheck} for why this is not simply "twice the timeout".
 * @param claimBatchSize   maximum jobs claimed per poll cycle. Caps how much work one worker can
 *                         hold, and therefore how long the last job in a batch waits before its
 *                         delivery starts — queue wait is the dominant term in lease sizing.
 * @param maxInFlight      ceiling on concurrently dispatching jobs. A claimed job's lease starts
 *                         running immediately, so claiming faster than you can deliver does not
 *                         get work done sooner — it just burns lease while jobs queue, and past a
 *                         point the reaper starts taking back jobs that are still in flight.
 *                         Virtual threads make it tempting to leave this unbounded; the limit
 *                         here is the lease, not the threads.
 * @param reaperIntervalMs how often to sweep for expired leases. This is a term in the lease
 *                         sizing inequality, not a free parameter: a job can sit expired for up to
 *                         one full interval before anyone notices, so the lease has to cover it.
 * @param reclaimBackoffMs how far into the future a reclaimed job's {@code nextRunAt} is pushed.
 *                         Without it the job is still due the instant it returns to PENDING and
 *                         gets re-claimed on the next 200ms poll — which, if the cause was a hung
 *                         endpoint, means hammering that endpoint five times a second.
 * @param maxClaims        pickups after which a job is quarantined rather than reclaimed again.
 *                         This is the poison-job guard: a payload that kills the worker never
 *                         fails a *delivery*, so {@code attempt} never rises and the retry limit
 *                         never triggers. Without a separate cap on pickups, one bad job walks
 *                         through the entire fleet, indefinitely.
 * @param changeStreamHorizonMs how far ahead the change-stream wakeup will hold a timer. Jobs due
 *                         beyond it are left to the poller. One poll interval is far too narrow —
 *                         it excludes the exact case the feature exists for, a job created a few
 *                         seconds before it is due — while an unbounded horizon would hold a timer
 *                         for every job scheduled next year. 30s covers "run in N seconds" without
 *                         retaining much.
 */
@ConfigurationProperties(prefix = "chronos")
public record ChronosProperties(

        @DefaultValue("262144") int maxPayloadBytes,

        @DefaultValue("5000") int defaultTimeoutMs,

        @DefaultValue("default") String defaultTenantId,

        @DefaultValue("200") long pollIntervalMs,

        @DefaultValue("30000") long leaseDurationMs,

        @DefaultValue("50") int claimBatchSize,

        @DefaultValue("200") int maxInFlight,

        @DefaultValue("10000") long reaperIntervalMs,

        @DefaultValue("5000") long reclaimBackoffMs,

        @DefaultValue("10") int maxClaims,

        @DefaultValue("30000") long changeStreamHorizonMs
) {

    public Duration leaseDuration() {
        return Duration.ofMillis(leaseDurationMs);
    }

    public Duration defaultTimeout() {
        return Duration.ofMillis(defaultTimeoutMs);
    }
}
