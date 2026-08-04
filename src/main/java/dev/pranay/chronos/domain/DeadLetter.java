package dev.pranay.chronos.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A job that ran out of road.
 *
 * <p>Two ways in: retries exhausted, or a response that retrying cannot fix (a malformed request
 * gets the same 400 five times). Either way the job stops, and the interesting question becomes
 * what a human does next — which is why this stores a <em>full snapshot</em> rather than a
 * reference. The original job can be edited or deleted afterwards, and a dead letter that says
 * "job 507f… failed" without recording what that job actually was is an audit trail you cannot act
 * on.
 *
 * <p>The snapshot is also what makes replay a clone rather than a resurrection: the failed job is
 * left exactly as it died, and a new one is created from the snapshot. Nothing about the historical
 * record changes when someone retries it.
 */
@Document(collection = "dead_letters")
public class DeadLetter {

    @Id
    private String id;

    private String jobId;
    private String tenantId;

    /** Everything the job was at the moment it died. */
    private Job jobSnapshot;

    private Instant failedAt;
    private int totalAttempts;
    private String lastError;
    private Integer lastStatusCode;

    /** Set when an operator replays this. Non-null means it has already been sent back out. */
    private Instant replayedAt;

    /** Id of the job created by the replay, so the two are traceable in both directions. */
    private String replayJobId;

    protected DeadLetter() {
        // for Spring Data
    }

    public static DeadLetter from(Job job, String reason) {
        DeadLetter letter = new DeadLetter();
        letter.jobId = job.getId();
        letter.tenantId = job.getTenantId();
        letter.jobSnapshot = job;
        letter.failedAt = Instant.now();
        letter.totalAttempts = job.getAttempt();
        letter.lastError = reason;
        letter.lastStatusCode = job.getLastStatusCode();
        return letter;
    }

    public void markReplayed(String newJobId) {
        this.replayedAt = Instant.now();
        this.replayJobId = newJobId;
    }

    public boolean isReplayed() {
        return replayedAt != null;
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

    public Job getJobSnapshot() {
        return jobSnapshot;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public int getTotalAttempts() {
        return totalAttempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }

    public String getReplayJobId() {
        return replayJobId;
    }
}
