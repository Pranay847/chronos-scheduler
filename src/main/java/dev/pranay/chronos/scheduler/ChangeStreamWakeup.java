package dev.pranay.chronos.scheduler;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import dev.pranay.chronos.config.ChronosProperties;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.messaging.ChangeStreamRequest;
import org.springframework.data.mongodb.core.messaging.DefaultMessageListenerContainer;
import org.springframework.data.mongodb.core.messaging.MessageListener;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fires imminent jobs at their scheduled instant instead of at the next poll tick.
 *
 * <h2>What this actually buys, and what it does not</h2>
 *
 * <p>Polling at interval {@code T} gives mean drift {@code T/2} and worst case {@code T}. That is a
 * property of the design, not a bug, and it is why the measured p99 sits just under 200ms with a
 * 200ms interval. Dropping {@code T} globally would improve it — at the cost of every worker
 * querying the database that much more often, forever, whether or not there is anything to do.
 *
 * <p>A change stream buys the same latency for a fraction of the cost: MongoDB tells us the moment
 * a job is created or rescheduled, and we set a one-shot timer for that job's exact
 * {@code nextRunAt}. Jobs far in the future are ignored entirely — the poller collects those
 * perfectly well.
 *
 * <p>Note that an insert notification alone is <em>not</em> enough. A job created now to run in two
 * seconds cannot be claimed now; waking immediately would find nothing and change nothing. The
 * latency win comes from scheduling a wake-up <em>at</em> {@code nextRunAt}, which is why this
 * class holds timers rather than simply nudging the poller.
 *
 * <h2>Why polling stays</h2>
 *
 * <p>This is layered on top of the poller and never replaces it. Change streams add a persistent
 * connection per worker and a dependency on the oplog; a resumable stream that has fallen too far
 * behind its resume token cannot recover, and a stream can simply die. When any of that happens the
 * poller is still running, so the failure mode is <em>drift returns to the polling baseline</em>
 * rather than jobs stop firing. That is the whole reason this is safe to add.
 *
 * <p>Requires a replica set. Change streams read the oplog, and a standalone mongod has none —
 * which is why the compose stack has run as a single-node replica set since Phase 1 rather than
 * retrofitting one here.
 */
@Component
// BOTH properties must hold. The wakeup does nothing except accelerate the poller, so it cannot
// outlive it — and tests that disable the poller to drive the claim path by hand would otherwise
// fail to start a context at all, because this bean requires one.
@ConditionalOnProperty(
        name = {"chronos.poller.enabled", "chronos.change-stream.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class ChangeStreamWakeup {

    private static final Logger log = LoggerFactory.getLogger(ChangeStreamWakeup.class);

    /** Cap on outstanding timers, so a bulk insert of a million future jobs cannot exhaust memory. */
    private static final int MAX_PENDING_WAKEUPS = 10_000;

    /**
     * How many jobs one wakeup may claim.
     *
     * <p>Small on purpose. Each timer was scheduled for one particular job; letting it claim a full
     * batch means a handful of early wakeups swallow the whole queue while every remaining timer
     * still fires, finds nothing, and occupies a thread. Claiming a couple absorbs the common case
     * of several jobs sharing an instant without turning each wakeup into a batch poll.
     */
    private static final int WAKEUP_CLAIM_BUDGET = 3;

    private final MongoTemplate mongoTemplate;
    private final PollerService poller;
    private final SchedulerMetrics metrics;
    private final WorkerIdentity worker;
    private final TaskScheduler taskScheduler;
    private final ChronosProperties properties;

    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private MessageListenerContainer container;

    public ChangeStreamWakeup(MongoTemplate mongoTemplate,
                              PollerService poller,
                              SchedulerMetrics metrics,
                              WorkerIdentity worker,
                              @org.springframework.beans.factory.annotation.Qualifier("wakeupScheduler")
                              TaskScheduler taskScheduler,
                              ChronosProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.poller = poller;
        this.metrics = metrics;
        this.worker = worker;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        start();
    }

    private void start() {
        container = new DefaultMessageListenerContainer(mongoTemplate);
        container.start();

        MessageListener<ChangeStreamDocument<Document>, Document> listener = message -> {
            Document job = message.getBody();
            if (job != null) {
                onJobChanged(job);
            }
        };

        // Inserts are new jobs. Updates and replaces matter too: a retry (§5.2) and a cron rollover
        // (§4) both move nextRunAt on an existing document, and those are just as worth waking for
        // as a brand-new job. Filtering to inserts alone would leave every retry on the poll path.
        ChangeStreamRequest<Document> request = ChangeStreamRequest.builder(listener)
                .collection("jobs")
                .filter(Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("operationType").in("insert", "update", "replace"))))
                // Updates carry only a delta by default; without this the event would tell us a job
                // changed but not when it is now due.
                .fullDocumentLookup(FullDocument.UPDATE_LOOKUP)
                .build();

        container.register(request, Document.class);
        log.info("Change-stream wakeup active on 'jobs' (worker {})", worker.id());
    }

    private void onJobChanged(Document job) {
        Object status = job.get("status");
        Object nextRunAt = job.get("nextRunAt");
        if (!"PENDING".equals(status) || !(nextRunAt instanceof Date due)) {
            return;
        }

        Instant runAt = due.toInstant();
        Duration until = Duration.between(Instant.now(), runAt);

        // Anything beyond the horizon is the poller's job — a timer for a job due next year would
        // be held for a year and gain nothing.
        //
        // The horizon was originally one poll interval, and that was wrong in a way worth
        // recording: it excluded the exact case this feature exists for. A job created three
        // seconds before it is due sits outside a 200ms window, so no timer was ever armed, and a
        // benchmark of the feature measured pure variance. `scheduler.wakeup.early` reading zero
        // while jobs kept firing is what exposed it.
        if (until.compareTo(Duration.ofMillis(properties.changeStreamHorizonMs())) > 0) {
            return;
        }

        String jobId = String.valueOf(job.get("_id"));
        if (pending.size() >= MAX_PENDING_WAKEUPS) {
            return;   // poller still covers it; this only ever degrades to the baseline
        }

        // Every worker sees every event, so without this they would all wake on the same
        // millisecond and N-1 of them would lose the claim race — turning a latency optimisation
        // into a contention generator. A few milliseconds of spread is far below the drift being
        // eliminated and lets one worker usually win outright.
        long jitterMs = ThreadLocalRandom.current().nextLong(0, 10);
        Instant candidate = runAt.plusMillis(jitterMs);
        Instant wakeAt = candidate.isBefore(Instant.now()) ? Instant.now() : candidate;

        // computeIfAbsent, so a job updated several times in quick succession holds one timer
        // rather than accumulating one per event.
        pending.computeIfAbsent(jobId, id -> taskScheduler.schedule(() -> {
            pending.remove(id);
            try {
                // Claim a SMALL budget, not a full batch. This timer exists for one specific job;
                // claiming 50 means the first few wakeups drain the queue while the remaining
                // timers all fire, find nothing, and burn scheduler threads for no benefit.
                int claimed = poller.wakeAndClaim(WAKEUP_CLAIM_BUDGET);
                if (claimed > 0) {
                    metrics.recordEarlyWakeup(claimed);
                }
            } catch (RuntimeException e) {
                // Never let a wakeup failure propagate into the scheduler thread. The poller is
                // still running; the worst case is this job fires on the normal cycle instead.
                log.warn("Change-stream wakeup failed for job {}", id, e);
            }
        }, wakeAt));
    }

    @PreDestroy
    public void stop() {
        pending.values().forEach(f -> f.cancel(false));
        pending.clear();
        if (container != null && container.isRunning()) {
            container.stop();
        }
        log.info("Change-stream wakeup stopped (worker {})", worker.id());
    }

    int pendingWakeups() {
        return pending.size();
    }
}
