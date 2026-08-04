package dev.pranay.chronos;

import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobStatus;
import dev.pranay.chronos.domain.RetryPolicy;
import dev.pranay.chronos.domain.Schedule;
import dev.pranay.chronos.domain.ScheduleType;
import dev.pranay.chronos.domain.Target;
import dev.pranay.chronos.repository.JobCompletion;
import dev.pranay.chronos.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim mechanism, driven by hand.
 *
 * <p>The poller is switched off here on purpose. These tests assert the semantics of claiming and
 * completing directly, and a 200ms loop racing them in the background would make failures
 * intermittent and unreadable.
 *
 * <p>Everything here is a Phase 3 invariant being locked in early, while the code is small enough
 * to reason about. The concurrency and crash-recovery tests build on exactly these guarantees.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "chronos.poller.enabled=false")
class ClaimSemanticsTest {

    @Autowired
    private JobRepository jobRepository;

    private static final Duration LEASE = Duration.ofSeconds(30);

    @BeforeEach
    void reset() {
        jobRepository.deleteAll();
    }

    /**
     * Claiming increments {@code claimCount} and leaves {@code attempt} alone.
     *
     * <p>The distinction looks pedantic until you follow it through. If claiming incremented
     * {@code attempt}, then a worker that crashed before sending anything, or a job bounced by an
     * open circuit breaker in Phase 5, would spend one of the customer's retries. A destination
     * that is down for ten minutes would exhaust {@code maxAttempts} and dead-letter every job
     * aimed at it — none of which were ever sent.
     */
    @Test
    void claimingCountsAsAPickupNotAnAttempt() {
        Job job = jobRepository.save(dueJob("counters"));

        Job claimed = jobRepository.claimNextDueJob("worker-a", LEASE).orElseThrow();

        assertThat(claimed.getClaimCount()).isEqualTo(1);
        assertThat(claimed.getAttempt()).as("no delivery has been attempted yet").isZero();
        assertThat(claimed.getStatus()).isEqualTo(JobStatus.CLAIMED);
        assertThat(claimed.getLockedBy()).isEqualTo("worker-a");
        assertThat(claimed.getLockExpiresAt()).isAfter(Instant.now());
        assertThat(job.getId()).isEqualTo(claimed.getId());
    }

    /**
     * {@code returnNew(true)} is doing its job.
     *
     * <p>Without it {@code findAndModify} hands back the pre-update document, so the caller acts on
     * a job whose {@code claimCount} is stale and whose {@code lockExpiresAt} is still null — and
     * every counter in the system reads one behind for reasons that are miserable to trace.
     */
    @Test
    void claimReturnsThePostUpdateDocument() {
        jobRepository.save(dueJob("post-update"));

        Job claimed = jobRepository.claimNextDueJob("worker-a", LEASE).orElseThrow();

        assertThat(claimed.getLockExpiresAt()).as("null here means returnNew(true) is missing").isNotNull();
        assertThat(claimed.getClaimCount()).as("0 here means returnNew(true) is missing").isEqualTo(1);
    }

    /** Two workers, one due job: exactly one wins, with no transaction and no lock service. */
    @Test
    void onlyOneWorkerCanClaimAGivenJob() {
        jobRepository.save(dueJob("contested"));

        Optional<Job> first = jobRepository.claimNextDueJob("worker-a", LEASE);
        Optional<Job> second = jobRepository.claimNextDueJob("worker-b", LEASE);

        assertThat(first).isPresent();
        assertThat(second).as("the job is no longer PENDING, so the filter cannot match it").isEmpty();
    }

    @Test
    void jobsAreClaimedOldestFirst() {
        Job late = jobRepository.save(dueJobAt("late", Instant.now().minus(1, ChronoUnit.MINUTES)));
        Job early = jobRepository.save(dueJobAt("early", Instant.now().minus(10, ChronoUnit.MINUTES)));

        Job first = jobRepository.claimNextDueJob("worker-a", LEASE).orElseThrow();
        Job second = jobRepository.claimNextDueJob("worker-a", LEASE).orElseThrow();

        assertThat(first.getId()).as("sort by nextRunAt ASC is what keeps the backlog fair").isEqualTo(early.getId());
        assertThat(second.getId()).isEqualTo(late.getId());
    }

    @Test
    void jobsThatAreNotYetDueAreNotClaimed() {
        jobRepository.save(dueJobAt("future", Instant.now().plus(1, ChronoUnit.HOURS)));

        assertThat(jobRepository.claimNextDueJob("worker-a", LEASE)).isEmpty();
    }

    /**
     * A worker that lost its lease cannot write its result.
     *
     * <p>This is the single most important assertion in Phase 2, and it is the one the atomic
     * claim does <em>not</em> give you for free. The claim stops a second worker from starting the
     * job; nothing stops a slow worker whose lease expired mid-delivery from finishing it and
     * overwriting whoever legitimately owns it now. Once cron exists, that second write also rolls
     * the schedule forward a second time and silently drops a firing.
     */
    @Test
    void aWorkerThatLostItsLeaseCannotCompleteTheJob() {
        Job job = jobRepository.save(dueJob("stolen"));

        // Worker A claims, then its lease is taken over by worker B — exactly what the reaper
        // will do in Phase 3 when A looks dead.
        Job claimedByA = jobRepository.claimNextDueJob("worker-a", LEASE).orElseThrow();
        jobRepository.releaseIfOwned(claimedByA.getId(), "worker-a");
        Job claimedByB = jobRepository.claimNextDueJob("worker-b", LEASE).orElseThrow();

        // A finally returns from its HTTP call and tries to record success.
        boolean aWrote = jobRepository.completeIfOwned(job.getId(), "worker-a",
                JobCompletion.succeeded(1, Instant.now(), 200));

        assertThat(aWrote).as("worker A no longer holds the lease and must be refused").isFalse();
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .as("A's write must not have landed")
                .isEqualTo(JobStatus.CLAIMED);

        // B, which does own it, writes normally.
        boolean bWrote = jobRepository.completeIfOwned(claimedByB.getId(), "worker-b",
                JobCompletion.succeeded(1, Instant.now(), 200));

        assertThat(bWrote).isTrue();
        Job finished = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(finished.getLockedBy()).isNull();
    }

    /** An expired lease is refused even though the worker id still matches. */
    @Test
    void anExpiredLeaseCannotCompleteTheJob() {
        jobRepository.save(dueJob("expired"));
        Job claimed = jobRepository.claimNextDueJob("worker-a", Duration.ofMillis(1)).orElseThrow();

        sleep(50);

        boolean wrote = jobRepository.completeIfOwned(claimed.getId(), "worker-a",
                JobCompletion.succeeded(1, Instant.now(), 200));

        assertThat(wrote).as("lockExpiresAt is in the past, so ownership has lapsed").isFalse();
    }

    @Test
    void countDueIgnoresFutureAndClaimedJobs() {
        jobRepository.save(dueJob("due-1"));
        jobRepository.save(dueJob("due-2"));
        jobRepository.save(dueJobAt("future", Instant.now().plus(1, ChronoUnit.HOURS)));
        jobRepository.claimNextDueJob("worker-a", LEASE);

        assertThat(jobRepository.countDue(Instant.now()))
                .as("one of the two due jobs is now CLAIMED, and the future one was never eligible")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private static Job dueJob(String name) {
        return dueJobAt(name, Instant.now().minusSeconds(1));
    }

    private static Job dueJobAt(String name, Instant runAt) {
        return Job.create(
                "default",
                name,
                new Schedule(ScheduleType.ONE_TIME, runAt, null, "UTC", null),
                new Target("https://example.invalid/hook", "POST", Map.of(), Map.of("n", 1), 5000),
                RetryPolicy.DEFAULT,
                runAt);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
