package dev.pranay.chronos;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobExecution;
import dev.pranay.chronos.domain.JobStatus;
import dev.pranay.chronos.domain.RetryPolicy;
import dev.pranay.chronos.domain.Schedule;
import dev.pranay.chronos.domain.ScheduleType;
import dev.pranay.chronos.domain.Target;
import dev.pranay.chronos.repository.JobExecutionRepository;
import dev.pranay.chronos.repository.JobRepository;
import dev.pranay.chronos.scheduler.DispatcherService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test the whole design exists to pass.
 *
 * <pre>
 *   Given 1,000 jobs all due at T
 *   When  10 workers poll concurrently
 *   Then  each job has exactly one execution record, with attempt = 1
 *   And   no job is left PENDING or CLAIMED
 * </pre>
 *
 * <p>Assertions are on the {@code job_executions} collection, not on log output. A log line says a
 * worker <em>believed</em> it delivered something; an execution record is the artifact a duplicate
 * would actually show up in.
 *
 * <p>The ten workers are real distinct identities racing the same {@code findAndModify}, not ten
 * calls on one identity — the whole question is whether two workers can end up holding the same
 * document, and that is only meaningful if their ids differ.
 *
 * <p>The poller is off. These threads drive the claim loop directly so the test controls
 * concurrency instead of racing a background timer for it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "chronos.poller.enabled=false",
        "chronos.reaper.enabled=false"
})
class ConcurrencyTest {

    private static final int JOB_COUNT = 1_000;
    private static final int WORKER_COUNT = 10;
    private static final Duration LEASE = Duration.ofSeconds(60);

    private static WireMockServer receiver;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExecutionRepository executionRepository;

    @Autowired
    private DispatcherService dispatcher;

    @BeforeAll
    static void startReceiver() {
        // Default container threads would become the bottleneck with ten workers in flight and
        // make this a test of WireMock rather than of the claim.
        receiver = new WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(60));
        receiver.start();
        receiver.stubFor(post(urlEqualTo("/sink")).willReturn(aResponse().withStatus(200)));
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop();
    }

    @BeforeEach
    void reset() {
        jobRepository.deleteAll();
        executionRepository.deleteAll();
    }

    @Test
    void athousandJobsAndTenWorkersProduceExactlyOneExecutionEach() throws Exception {
        Instant dueAt = Instant.now().minusSeconds(1);
        List<Job> seeded = IntStream.range(0, JOB_COUNT)
                .mapToObj(i -> dueJob("concurrent-" + i, dueAt))
                .collect(Collectors.toList());
        jobRepository.saveAll(seeded);

        assertThat(jobRepository.countDue(Instant.now())).isEqualTo(JOB_COUNT);

        AtomicInteger totalClaimed = new AtomicInteger();
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(WORKER_COUNT);
        ExecutorService workers = Executors.newFixedThreadPool(WORKER_COUNT);

        for (int w = 0; w < WORKER_COUNT; w++) {
            String workerId = "worker-" + w;
            workers.submit(() -> {
                try {
                    // Release all ten at once. Staggered starts would let each worker drain the
                    // queue politely in turn, which is not the scenario being tested.
                    startGun.await();
                    while (true) {
                        Optional<Job> claimed = jobRepository.claimNextDueJob(workerId, LEASE);
                        if (claimed.isEmpty()) {
                            break;
                        }
                        totalClaimed.incrementAndGet();
                        dispatcher.dispatch(claimed.get(), workerId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        startGun.countDown();
        assertThat(finished.await(180, TimeUnit.SECONDS)).as("workers did not finish in time").isTrue();
        workers.shutdown();

        // Every job was claimed exactly once in total. More than JOB_COUNT here would mean two
        // workers held the same document — the failure the atomic claim exists to prevent.
        assertThat(totalClaimed.get()).isEqualTo(JOB_COUNT);

        List<JobExecution> executions = executionRepository.findAll();
        assertThat(executions).hasSize(JOB_COUNT);

        assertThat(executions.stream().map(JobExecution::getJobId).distinct().count())
                .as("one execution per job, no duplicates")
                .isEqualTo(JOB_COUNT);

        assertThat(executions.stream().allMatch(e -> e.getAttempt() == 1))
                .as("attempt must be 1 everywhere — a 2 means a job was delivered twice")
                .isTrue();

        assertThat(executions.stream().map(JobExecution::getIdempotencyKey).distinct().count())
                .as("keys are per-firing, so 1000 firings means 1000 distinct keys")
                .isEqualTo(JOB_COUNT);

        List<Job> remaining = jobRepository.findAll();
        assertThat(remaining).hasSize(JOB_COUNT);
        assertThat(remaining.stream().filter(j -> j.getStatus() == JobStatus.PENDING).count())
                .as("nothing may be left unclaimed").isZero();
        assertThat(remaining.stream().filter(j -> j.getStatus() == JobStatus.CLAIMED).count())
                .as("nothing may be left holding a lease").isZero();
        assertThat(remaining.stream().filter(j -> j.getStatus() == JobStatus.SUCCEEDED).count())
                .isEqualTo(JOB_COUNT);

        // Every worker did some of the work. If one thread happened to drain the entire queue the
        // assertions above would still pass while testing almost nothing.
        long workersUsed = executions.stream().map(JobExecution::getWorkerId).distinct().count();
        assertThat(workersUsed).as("work should have spread across the fleet").isGreaterThan(1);
    }

    private static Job dueJob(String name, Instant dueAt) {
        return Job.create(
                "default",
                name,
                new Schedule(ScheduleType.ONE_TIME, dueAt, null, "UTC", null),
                new Target(receiver.baseUrl() + "/sink", "POST", Map.of(), Map.of("n", 1), 5000),
                RetryPolicy.DEFAULT,
                dueAt);
    }
}
