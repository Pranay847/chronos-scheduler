package dev.pranay.chronos;

import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobStatus;
import dev.pranay.chronos.domain.RetryPolicy;
import dev.pranay.chronos.domain.Schedule;
import dev.pranay.chronos.domain.ScheduleType;
import dev.pranay.chronos.domain.Target;
import dev.pranay.chronos.repository.JobRepository;
import dev.pranay.chronos.scheduler.PollerService;
import dev.pranay.chronos.scheduler.WorkerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Handing work back on the way out.
 *
 * <p>Crash recovery already covers the ungraceful case — a killed worker's leases expire and the
 * reaper collects them. But a deliberate stop is not a crash, and treating it like one wastes the
 * entire lease duration on every deploy: from the database's point of view a clean shutdown and a
 * SIGKILL look identical, so jobs the worker never even started sit unclaimable for thirty seconds
 * while a healthy replacement is already running.
 *
 * <p>This is also what gives the Phase 8 chaos test a meaningful contrast. SIGKILL should produce
 * duplicates bounded by whatever was in flight at the moment of death; a graceful stop should
 * produce none at all. Without shutdown handling the two are the same number and the chaos
 * document has nothing to say.
 *
 * <p>The poller loop is disabled so the test controls which jobs are claimed; {@code shutdown()} is
 * the same method {@code @PreDestroy} invokes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "chronos.poller.enabled=true",
        "chronos.reaper.enabled=false",
        // Long enough that the test would clearly fail if it relied on expiry instead of shutdown.
        "chronos.lease-duration-ms=600000",
        "chronos.poll-interval-ms=86400000"
})
class GracefulShutdownTest {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PollerService poller;

    @Autowired
    private WorkerIdentity worker;

    @BeforeEach
    void reset() {
        jobRepository.deleteAll();
    }

    @Test
    void shutdownReturnsClaimedButUnstartedJobsToThePool() {
        jobRepository.save(dueJob("stranded-1"));
        jobRepository.save(dueJob("stranded-2"));

        jobRepository.claimNextDueJob(worker.id(), Duration.ofMinutes(10));
        jobRepository.claimNextDueJob(worker.id(), Duration.ofMinutes(10));

        assertThat(jobRepository.findAll().stream().filter(j -> j.getStatus() == JobStatus.CLAIMED).count())
                .isEqualTo(2);

        poller.shutdown();

        assertThat(jobRepository.findAll())
                .as("both jobs must be immediately claimable again, not held for the full 10-minute lease")
                .allSatisfy(job -> {
                    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
                    assertThat(job.getLockedBy()).isNull();
                    assertThat(job.getLockExpiresAt()).isNull();
                });
    }

    /**
     * Shutdown releases only this worker's claims.
     *
     * <p>A rolling deploy stops one worker while its peers keep working. Releasing their leases
     * too would hand live jobs to a third worker and manufacture the duplicate deliveries the
     * lease exists to avoid — turning an orderly deploy into the exact incident it should prevent.
     */
    @Test
    void shutdownDoesNotTouchAnotherWorkersClaims() {
        jobRepository.save(dueJob("mine"));
        jobRepository.save(dueJob("theirs"));

        jobRepository.claimNextDueJob(worker.id(), Duration.ofMinutes(10));
        jobRepository.claimNextDueJob("some-other-worker", Duration.ofMinutes(10));

        poller.shutdown();

        Job theirs = jobRepository.findAll().stream()
                .filter(j -> "some-other-worker".equals(j.getLockedBy()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the peer's claim was released — it must not be"));

        assertThat(theirs.getStatus()).isEqualTo(JobStatus.CLAIMED);
        assertThat(theirs.getLockExpiresAt()).isNotNull();
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
}
