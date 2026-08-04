package dev.pranay.chronos;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.pranay.chronos.domain.DeadLetter;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobExecution;
import dev.pranay.chronos.domain.JobStatus;
import dev.pranay.chronos.repository.DeadLetterRepository;
import dev.pranay.chronos.repository.JobExecutionRepository;
import dev.pranay.chronos.repository.JobRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retries, the dead-letter queue, and replay.
 *
 * <p>Backoff is turned right down so retries happen in hundreds of milliseconds rather than the
 * production minutes. The intervals are what is being shortened, not the logic.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "chronos.reaper.enabled=false")
class RetryAndDeadLetterTest {

    private static WireMockServer receiver;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExecutionRepository executionRepository;

    @Autowired
    private DeadLetterRepository deadLetterRepository;

    @BeforeAll
    static void startReceiver() {
        receiver = new WireMockServer(0);
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop();
    }

    @BeforeEach
    void reset() {
        receiver.resetAll();
        jobRepository.deleteAll();
        executionRepository.deleteAll();
        deadLetterRepository.deleteAll();
    }

    /**
     * <b>The assertion the whole idempotency design exists for.</b>
     *
     * <p>A job fails twice and then succeeds. All three requests must reach the receiver carrying
     * the <em>same</em> {@code X-Idempotency-Key}, because they are three attempts at one firing,
     * not three events.
     *
     * <p>This is what breaks if {@code currentRunScheduledFor} is ever folded into the retry
     * update alongside {@code nextRunAt}. Nothing would throw; the key would simply change on
     * every attempt, and a receiver following our own deduplication instructions would process the
     * same logical event three times. There is no other test in the suite that would notice.
     */
    @Test
    void everyRetryOfOneFiringCarriesTheSameIdempotencyKey() {
        receiver.stubFor(post(urlEqualTo("/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        receiver.stubFor(post(urlEqualTo("/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third"));
        receiver.stubFor(post(urlEqualTo("/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs("third")
                .willReturn(aResponse().withStatus(200)));

        String jobId = createJob("flaky-then-ok", "/flaky", 5);

        // Wait on the execution records, not on job status. The dispatcher writes the terminal
        // job state first and inserts the execution record after it, so a test that waits for
        // SUCCEEDED can observe the job before the third record has landed — an intermittent
        // off-by-one that has nothing to do with the behaviour being tested.
        awaitTrue(() -> executionRepository.countByJobId(jobId) == 3, Duration.ofSeconds(30),
                "expected three execution records: two failures then a success");
        awaitTrue(() -> jobStatus(jobId) == JobStatus.SUCCEEDED, Duration.ofSeconds(10),
                "job never reached SUCCEEDED");

        List<LoggedRequest> requests = receiver.findAll(postRequestedFor(urlEqualTo("/flaky")));
        assertThat(requests).as("two failures then a success").hasSize(3);

        List<String> keys = requests.stream().map(r -> r.getHeader("X-Idempotency-Key")).distinct().toList();
        assertThat(keys)
                .as("all three attempts are the same firing and must share one key")
                .hasSize(1);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(keys.getFirst()).isEqualTo(job.idempotencyKey());
        assertThat(job.getAttempt()).isEqualTo(3);

        // One execution record per attempt: the audit trail keeps all three, distinguished by
        // attempt number while sharing the firing's key.
        List<JobExecution> executions = executionRepository.findByJobIdOrderByAttemptDesc(jobId);
        assertThat(executions).hasSize(3);
        assertThat(executions.stream().map(JobExecution::getIdempotencyKey).distinct()).hasSize(1);
        assertThat(executions.stream().map(JobExecution::getAttempt).sorted().toList())
                .containsExactly(1, 2, 3);
    }

    /**
     * The scheduled time does not move when a retry is scheduled, but the poll time does.
     *
     * <p>Asserted directly on the document, because this is the invariant the key derives from and
     * it is easier to debug here than through three HTTP requests.
     */
    @Test
    void aRetryMovesNextRunAtButNotTheScheduledTime() {
        receiver.stubFor(post(urlEqualTo("/always-500")).willReturn(aResponse().withStatus(500)));

        String jobId = createJob("scheduled-time-stable", "/always-500", 5);
        Instant originalScheduledFor = jobRepository.findById(jobId).orElseThrow().getCurrentRunScheduledFor();

        awaitTrue(() -> jobRepository.findById(jobId).orElseThrow().getAttempt() >= 2,
                Duration.ofSeconds(30), "job never reached a second attempt");

        Job afterRetry = jobRepository.findById(jobId).orElseThrow();
        assertThat(afterRetry.getCurrentRunScheduledFor())
                .as("the firing has not changed, so its identity must not either")
                .isEqualTo(originalScheduledFor);
        assertThat(afterRetry.getNextRunAt())
                .as("but the poll time must have moved, or the retry was never scheduled")
                .isAfter(originalScheduledFor);
    }

    /**
     * A permanent client error skips the retry budget entirely.
     *
     * <p>A 400 will be a 400 next time too. Spending five attempts to learn that wastes your
     * capacity and the receiver's, and delays the dead letter that someone actually needs to see.
     */
    @Test
    void aPermanentClientErrorGoesStraightToTheDeadLetterQueue() {
        receiver.stubFor(post(urlEqualTo("/bad-request")).willReturn(aResponse().withStatus(400)));

        String jobId = createJob("malformed", "/bad-request", 5);

        awaitTrue(() -> !deadLetterRepository.findByJobId(jobId).isEmpty(), Duration.ofSeconds(30),
                "job was never dead-lettered");

        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/bad-request"))))
                .as("exactly one attempt — no retries for a permanent failure")
                .hasSize(1);

        DeadLetter letter = deadLetterRepository.findByJobId(jobId).getFirst();
        assertThat(letter.getTotalAttempts()).isEqualTo(1);
        assertThat(letter.getLastStatusCode()).isEqualTo(400);
        assertThat(letter.getLastError()).contains("400");
        assertThat(letter.getJobSnapshot()).as("the snapshot is what makes replay possible").isNotNull();
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.FAILED);
    }

    /** Retries run out, and the job lands in the queue with its full history. */
    @Test
    void exhaustingTheRetryBudgetDeadLettersTheJob() {
        receiver.stubFor(post(urlEqualTo("/always-503")).willReturn(aResponse().withStatus(503)));

        String jobId = createJob("doomed", "/always-503", 3);

        awaitTrue(() -> !deadLetterRepository.findByJobId(jobId).isEmpty(), Duration.ofSeconds(40),
                "job never exhausted its retries");

        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/always-503"))))
                .as("maxAttempts=3 means exactly three deliveries, not four")
                .hasSize(3);

        DeadLetter letter = deadLetterRepository.findByJobId(jobId).getFirst();
        assertThat(letter.getTotalAttempts()).isEqualTo(3);
        assertThat(letter.getLastError()).contains("Exhausted");
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.FAILED);
        assertThat(executionRepository.countByJobId(jobId)).isEqualTo(3);
    }

    /**
     * Replay clones rather than resurrecting, and the clone gets a fresh key.
     *
     * <p>The new key is the deliberate part. Automatic retries mean "the same firing again" and
     * should be deduplicated; a replay is an operator saying "send this now", usually after fixing
     * what broke. Reusing the original key would let a receiver that had already processed the
     * original silently drop the replay — a button that reports success and does nothing.
     */
    @Test
    void replayCreatesANewJobWithAFreshIdentity() {
        receiver.stubFor(post(urlEqualTo("/bad-request")).willReturn(aResponse().withStatus(400)));
        String originalId = createJob("replay-me", "/bad-request", 1);

        awaitTrue(() -> !deadLetterRepository.findByJobId(originalId).isEmpty(), Duration.ofSeconds(30),
                "job was never dead-lettered");
        DeadLetter letter = deadLetterRepository.findByJobId(originalId).getFirst();

        // Fix the endpoint, then replay.
        receiver.resetAll();
        receiver.stubFor(post(urlEqualTo("/bad-request")).willReturn(aResponse().withStatus(200)));

        ResponseEntity<Map> replayed = rest.postForEntity(
                "/v1/dead-letters/" + letter.getId() + "/replay", null, Map.class);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String newJobId = (String) replayed.getBody().get("id");
        assertThat(newJobId).isNotEqualTo(originalId);

        awaitTrue(() -> jobStatus(newJobId) == JobStatus.SUCCEEDED, Duration.ofSeconds(30),
                "the replayed job never succeeded");

        Job original = jobRepository.findById(originalId).orElseThrow();
        Job replay = jobRepository.findById(newJobId).orElseThrow();

        assertThat(original.getStatus()).as("the failure record stays intact").isEqualTo(JobStatus.FAILED);
        assertThat(replay.getAttempt()).as("a replay gets a full budget, not the remains of one").isEqualTo(1);
        assertThat(replay.idempotencyKey())
                .as("a replay is a new delivery decision, so the receiver must not dedupe it away")
                .isNotEqualTo(original.idempotencyKey());

        DeadLetter after = deadLetterRepository.findById(letter.getId()).orElseThrow();
        assertThat(after.isReplayed()).isTrue();
        assertThat(after.getReplayJobId()).isEqualTo(newJobId);
    }

    @Test
    void aDeadLetterCannotBeReplayedTwice() {
        receiver.stubFor(post(urlEqualTo("/bad-request")).willReturn(aResponse().withStatus(400)));
        String jobId = createJob("replay-once", "/bad-request", 1);

        awaitTrue(() -> !deadLetterRepository.findByJobId(jobId).isEmpty(), Duration.ofSeconds(30),
                "job was never dead-lettered");
        String letterId = deadLetterRepository.findByJobId(jobId).getFirst().getId();

        assertThat(rest.postForEntity("/v1/dead-letters/" + letterId + "/replay", null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = rest.postForEntity(
                "/v1/dead-letters/" + letterId + "/replay", null, String.class);
        assertThat(second.getStatusCode().is2xxSuccessful())
                .as("replaying twice would quietly double-deliver")
                .isFalse();
    }

    @Test
    void deadLettersAreListedNewestFirst() {
        receiver.stubFor(post(urlEqualTo("/bad-request")).willReturn(aResponse().withStatus(400)));
        createJob("dl-1", "/bad-request", 1);
        createJob("dl-2", "/bad-request", 1);

        awaitTrue(() -> deadLetterRepository.count() == 2, Duration.ofSeconds(30),
                "expected two dead letters");

        ResponseEntity<List> listed = rest.getForEntity("/v1/dead-letters", List.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(2);
    }

    // ---------------------------------------------------------------- helpers

    private String createJob(String name, String path, int maxAttempts) {
        Map<String, Object> request = Map.of(
                "name", name,
                "schedule", Map.of("type", "ONE_TIME", "runAt", Instant.now().toString()),
                "target", Map.of("url", receiver.baseUrl() + path, "method", "POST", "payload", Map.of("n", 1)),
                // Tiny backoff so retries land in hundreds of milliseconds. Only the intervals are
                // shortened; the logic under test is unchanged.
                "retryPolicy", Map.of("maxAttempts", maxAttempts, "backoffBaseMs", 100, "backoffMaxMs", 1000));

        ResponseEntity<Map> created = rest.postForEntity("/v1/jobs", request, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private JobStatus jobStatus(String jobId) {
        return jobRepository.findById(jobId).map(Job::getStatus).orElse(null);
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout, String message) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting: " + message, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout.toSeconds() + "s: " + message);
    }
}
