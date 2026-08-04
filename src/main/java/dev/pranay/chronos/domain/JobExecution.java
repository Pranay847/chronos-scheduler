package dev.pranay.chronos.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Duration;
import java.time.Instant;

/**
 * One delivery attempt. Append-only; this is the audit trail and the source of every metric.
 *
 * <p>{@code driftMs} is the number the whole project is measured by: how late we were relative to
 * when the job was <em>supposed</em> to fire. Note the baseline is {@code scheduledFor} — copied
 * from the job's {@code currentRunScheduledFor} — and not {@code nextRunAt}. Once retries exist,
 * {@code nextRunAt} is "when we decided to try again", so measuring against it would report the
 * drift of the retry rather than of the firing, and a badly-delayed job that eventually succeeded
 * would look punctual.
 *
 * <p>These records are high-volume and disposable: a TTL index expires them after 30 days, and
 * they are written with {@code w:1} rather than majority. Losing the tail of an audit log in a
 * failover is survivable; paying majority latency on every delivery is not.
 */
@Document(collection = "job_executions")
public class JobExecution {

    @Id
    private String id;

    private String jobId;
    private String tenantId;

    /** Stable across every retry of one firing. Sent to the receiver as {@code X-Idempotency-Key}. */
    private String idempotencyKey;

    private int attempt;

    /** When this firing was supposed to happen. */
    private Instant scheduledFor;

    /** When the delivery actually began. */
    private Instant startedAt;

    private Instant completedAt;

    /** {@code startedAt - scheduledFor}. The headline metric. */
    private long driftMs;

    /** Time spent inside the receiver's endpoint. */
    private long durationMs;

    private ExecutionOutcome outcome;
    private Integer responseCode;
    private String responseBodySnippet;
    private String errorMessage;

    private String workerId;

    /** Anchors the TTL index. Must be a BSON date or nothing is ever expired. */
    private Instant createdAt;

    protected JobExecution() {
        // for Spring Data
    }

    public static JobExecution record(Job job, String workerId, Instant startedAt, Instant completedAt,
                                      ExecutionOutcome outcome, Integer responseCode,
                                      String responseBodySnippet, String errorMessage) {
        JobExecution e = new JobExecution();
        e.jobId = job.getId();
        e.tenantId = job.getTenantId();
        e.idempotencyKey = job.idempotencyKey();
        e.attempt = job.getAttempt();
        e.scheduledFor = job.getCurrentRunScheduledFor();
        e.startedAt = startedAt;
        e.completedAt = completedAt;
        e.driftMs = Duration.between(job.getCurrentRunScheduledFor(), startedAt).toMillis();
        e.durationMs = Duration.between(startedAt, completedAt).toMillis();
        e.outcome = outcome;
        e.responseCode = responseCode;
        e.responseBodySnippet = responseBodySnippet;
        e.errorMessage = errorMessage;
        e.workerId = workerId;
        e.createdAt = Instant.now();
        return e;
    }

    public String getId() {
        return id;
    }

    public String getJobId() {
        return jobId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getDriftMs() {
        return driftMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public ExecutionOutcome getOutcome() {
        return outcome;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public String getResponseBodySnippet() {
        return responseBodySnippet;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
