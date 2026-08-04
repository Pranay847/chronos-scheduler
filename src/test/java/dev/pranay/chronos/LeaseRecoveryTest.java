package dev.pranay.chronos;

import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobStatus;
import dev.pranay.chronos.domain.RetryPolicy;
import dev.pranay.chronos.domain.Schedule;
import dev.pranay.chronos.domain.ScheduleType;
import dev.pranay.chronos.domain.Target;
import dev.pranay.chronos.repository.JobRepository;
import dev.pranay.chronos.scheduler.LeaseReaper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash recovery.
 *
 * <p>The reaper is driven by hand rather than by its 10s schedule, so these assert its semantics
 * instead of waiting on a timer. The scheduled version is the same method.
 *
 * <p>Both loops are disabled: the poller would claim the jobs out from under the test, and the
 * scheduled reaper would fire between the arrange and assert steps.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "chronos.poller.enabled=false",
        // The reaper bean must exist — the tests call sweep() directly. Pushing its interval out
        // rather than switching the bean off keeps it injectable while stopping the scheduled
        // sweep from firing between a test's arrange and assert steps.
        "chronos.reaper-interval-ms=86400000",
        "chronos.max-claims=3"
})
class LeaseRecoveryTest {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private LeaseReaper reaper;

    @BeforeEach
    void reset() {
        jobRepository.deleteAll();
    }

    /**
     * A dead worker's job comes back.
     *
     * <p>This is the entire crash-recovery story: no heartbeat, no failure detector, no membership
     * protocol. A worker that died holding a claim is indistinguishable from one that is merely
     * slow, so the only signal anyone can act on is the lease deadline passing.
     */
    @Test
    void anExpiredLeaseIsReturnedToThePool() {
        Job job = jobRepository.save(dueJob("crashed"));
        jobRepository.claimNextDueJob("worker-that-died", Duration.ofMillis(1));
        sleep(50);

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CLAIMED);

        reaper.sweep();

        Job recovered = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(recovered.getLockedBy()).isNull();
        assertThat(recovered.getLockExpiresAt()).isNull();
    }

    /**
     * A reclaimed job is pushed into the future, not made instantly due again.
     *
     * <p>Without the backoff the job is due the moment it returns to PENDING and is re-claimed on
     * the next 200ms poll. If the reason the lease expired was a hung endpoint — the common case —
     * that turns crash recovery into a five-per-second retry storm aimed at a service that is
     * already struggling.
     */
    @Test
    void aReclaimedJobIsRescheduledRatherThanImmediatelyDueAgain() {
        Job job = jobRepository.save(dueJob("hung-endpoint"));
        jobRepository.claimNextDueJob("worker-that-hung", Duration.ofMillis(1));
        sleep(50);

        Instant beforeSweep = Instant.now();
        reaper.sweep();

        Job recovered = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(recovered.getNextRunAt())
                .as("must be pushed out by the reclaim backoff, not left in the past")
                .isAfter(beforeSweep);

        assertThat(jobRepository.claimNextDueJob("eager-worker", Duration.ofSeconds(30)))
                .as("and therefore not claimable on the very next poll")
                .isEmpty();
    }

    /** A live worker's lease is left alone. */
    @Test
    void anUnexpiredLeaseIsNotDisturbed() {
        Job job = jobRepository.save(dueJob("still-working"));
        jobRepository.claimNextDueJob("busy-worker", Duration.ofMinutes(5));

        reaper.sweep();

        Job untouched = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(JobStatus.CLAIMED);
        assertThat(untouched.getLockedBy()).isEqualTo("busy-worker");
    }

    @Test
    void aReclaimedJobCanBeClaimedByADifferentWorker() {
        Job job = jobRepository.save(dueJob("handover"));
        jobRepository.claimNextDueJob("worker-a", Duration.ofMillis(1));
        sleep(50);
        reaper.sweep();

        // Undo the reclaim backoff so the job is due again without waiting it out.
        Job reclaimed = jobRepository.findById(job.getId()).orElseThrow();
        reclaimed.setNextRunAt(Instant.now().minusSeconds(1));
        jobRepository.save(reclaimed);

        Optional<Job> takenOver = jobRepository.claimNextDueJob("worker-b", Duration.ofSeconds(30));

        assertThat(takenOver).isPresent();
        assertThat(takenOver.get().getLockedBy()).isEqualTo("worker-b");
        assertThat(takenOver.get().getClaimCount())
                .as("second pickup, but still no delivery attempted")
                .isEqualTo(2);
        assertThat(takenOver.get().getAttempt()).isZero();
    }

    /**
     * A job that keeps killing workers is taken out of circulation.
     *
     * <p>The failure this prevents is specific and nasty: a payload that crashes the worker never
     * fails a <em>delivery</em>, so {@code attempt} never rises and the retry limit never fires.
     * Reclaim alone would hand it straight back out to kill the next worker, and the next, round
     * the fleet indefinitely. {@code claimCount} is the only counter that sees it happening.
     */
    @Test
    void aJobThatKeepsGettingPickedUpAndNeverFinishesIsQuarantined() {
        Job job = jobRepository.save(dueJob("poison"));

        // maxClaims is 3 for this test. Simulate three workers each dying mid-job.
        for (int i = 0; i < 3; i++) {
            jobRepository.claimNextDueJob("doomed-worker-" + i, Duration.ofMillis(1));
            sleep(30);
            if (i < 2) {
                reaper.sweep();
                Job back = jobRepository.findById(job.getId()).orElseThrow();
                back.setNextRunAt(Instant.now().minusSeconds(1));
                jobRepository.save(back);
            }
        }

        reaper.sweep();

        Job quarantined = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(quarantined.getStatus())
                .as("a job on its 3rd unfinished pickup must stop being handed out")
                .isEqualTo(JobStatus.FAILED);
        assertThat(quarantined.getLastError()).contains("suspected worker-crashing payload");
        assertThat(quarantined.getAttempt())
                .as("it was never actually delivered, so no attempt was ever made")
                .isZero();
    }

    private static Job dueJob(String name) {
        Instant dueAt = Instant.now().minusSeconds(1);
        return Job.create(
                "default",
                name,
                new Schedule(ScheduleType.ONE_TIME, dueAt, null, "UTC", null),
                new Target("https://example.invalid/hook", "POST", Map.of(), Map.of("n", 1), 5000),
                RetryPolicy.DEFAULT,
                dueAt);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
