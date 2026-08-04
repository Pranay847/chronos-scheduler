package dev.pranay.chronos.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A scheduled job. One document per job; the lease lives on the document itself so that
 * claiming is a single atomic {@code findAndModify} rather than a two-collection dance (§3).
 *
 * <h2>The three fields that are easy to get wrong</h2>
 *
 * <p><b>{@code nextRunAt} vs {@code currentRunScheduledFor}.</b> These are not the same thing
 * and the difference is the whole reason retries are safe. {@code nextRunAt} answers "when
 * should a worker next look at this?" and moves forward on every retry. {@code currentRunScheduledFor}
 * answers "what firing is this?" and does not move until a firing completes and the next one
 * begins. The idempotency key and the cron rollover base both derive from the latter. Deriving
 * either from {@code nextRunAt} produces a bug that passes every happy-path test: the key looks
 * stable until the first retry, at which point the receiver sees a brand-new event.
 *
 * <p><b>{@code attempt} vs {@code claimCount}.</b> {@code attempt} counts deliveries actually
 * made. {@code claimCount} counts pickups, including ones that ended in a worker crash or an
 * open circuit breaker. A single counter would let a ten-minute outage at the destination burn
 * a job's entire retry budget without a single request leaving the building.
 *
 * <p><b>{@code version}.</b> Optimistic-lock guard. The atomic claim stops two workers from
 * <em>starting</em> a job; it does nothing to stop a slow worker whose lease expired from
 * <em>finishing</em> one that has since been reassigned. Terminal writes re-assert ownership.
 */
@Document(collection = "jobs")
public class Job {

    @Id
    private String id;

    /** Owning tenant. Every query filters on this. Placeholder until auth lands in Phase 6. */
    private String tenantId;

    private String name;

    private Schedule schedule;
    private Target target;
    private RetryPolicy retryPolicy;

    private JobStatus status = JobStatus.PENDING;

    /** When to next poll for this job. Moves on every retry. */
    private Instant nextRunAt;

    /** The intended time of the firing currently in progress. Stable across retries. */
    private Instant currentRunScheduledFor;

    /** Delivery attempts made for the current firing. Incremented at dispatch, never at claim. */
    private int attempt;

    /** Times this job has been picked up, including crashes and breaker trips. */
    private int claimCount;

    @Version
    private Long version;

    /** Worker that currently owns the lease, or null. */
    private String lockedBy;

    /** Lease deadline. The reaper reclaims anything CLAIMED past this instant. */
    private Instant lockExpiresAt;

    private Instant lastRunAt;
    private Integer lastStatusCode;

    /** Why this job last failed. Also carries the reaper's reason when a job is quarantined. */
    private String lastError;

    /**
     * Set on a job created by {@code POST /v1/jobs/{id}/trigger}, pointing at the original.
     *
     * <p>A manual fire is a separate job precisely so it cannot disturb a recurring schedule; this
     * field is what keeps the two connected in the audit trail.
     */
    private String triggeredFrom;

    /** Set when the job reaches a terminal state. Anchors the retention policy. */
    private Instant completedAt;

    private Instant createdAt;
    private Instant updatedAt;

    protected Job() {
        // for Spring Data
    }

    /**
     * Builds a new PENDING job with both time fields initialised.
     *
     * <p>Both are set here rather than on first completion. Leaving
     * {@code currentRunScheduledFor} null until the first run means the first cron rollover
     * dereferences null, and it means the first firing has no stable identity to key on.
     */
    public static Job create(String tenantId, String name, Schedule schedule,
                             Target target, RetryPolicy retryPolicy, Instant firstRunAt) {
        Job job = new Job();
        Instant now = Instant.now();
        job.tenantId = tenantId;
        job.name = name;
        job.schedule = schedule;
        job.target = target;
        job.retryPolicy = retryPolicy == null ? RetryPolicy.DEFAULT : retryPolicy;
        job.status = JobStatus.PENDING;
        job.nextRunAt = firstRunAt;
        job.currentRunScheduledFor = firstRunAt;
        job.attempt = 0;
        job.claimCount = 0;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    /**
     * Stable per-firing identity, sent to receivers as {@code X-Idempotency-Key} so they can
     * deduplicate. Derived from the scheduled time, not the poll time — every retry of one
     * firing must produce the same string.
     */
    public String idempotencyKey() {
        return "job_" + id + "_run_" + currentRunScheduledFor.toEpochMilli();
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public void setSchedule(Schedule schedule) {
        this.schedule = schedule;
    }

    public Target getTarget() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public Instant getCurrentRunScheduledFor() {
        return currentRunScheduledFor;
    }

    public void setCurrentRunScheduledFor(Instant currentRunScheduledFor) {
        this.currentRunScheduledFor = currentRunScheduledFor;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public int getClaimCount() {
        return claimCount;
    }

    public void setClaimCount(int claimCount) {
        this.claimCount = claimCount;
    }

    public Long getVersion() {
        return version;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Instant getLockExpiresAt() {
        return lockExpiresAt;
    }

    public void setLockExpiresAt(Instant lockExpiresAt) {
        this.lockExpiresAt = lockExpiresAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public void setLastStatusCode(Integer lastStatusCode) {
        this.lastStatusCode = lastStatusCode;
    }

    public String getLastError() {
        return lastError;
    }

    public String getTriggeredFrom() {
        return triggeredFrom;
    }

    public void setTriggeredFrom(String triggeredFrom) {
        this.triggeredFrom = triggeredFrom;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
