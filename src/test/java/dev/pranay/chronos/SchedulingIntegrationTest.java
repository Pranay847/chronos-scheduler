package dev.pranay.chronos;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.pranay.chronos.domain.ExecutionOutcome;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobExecution;
import dev.pranay.chronos.domain.JobStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 acceptance: a job created for T+n actually fires at T+n, and the fact that it did is
 * visible in the metrics rather than only in a log line.
 *
 * <p>WireMock stands in for the customer's endpoint, which is the only way to assert the parts
 * that matter to a receiver — that the request arrived, and that it carried a stable idempotency
 * key.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SchedulingIntegrationTest {

    private static WireMockServer receiver;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExecutionRepository executionRepository;

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
    }

    /**
     * The Phase 2 "done when": a job created for T+2s fires within 500ms of T+2s.
     *
     * <p>The 500ms is asserted from the drift the service measured itself, not from a stopwatch in
     * the test. That distinction is the point of building the metrics harness now rather than in
     * Phase 8 — the number in the README has to come from the same instrument that will report it
     * in production, or it is just a claim.
     */
    @Test
    void jobFiresCloseToItsScheduledTimeAndReportsItsOwnDrift() {
        receiver.stubFor(post(urlEqualTo("/hooks/fire")).willReturn(aResponse().withStatus(200)));
        Instant runAt = Instant.now().plusSeconds(2);

        String jobId = createJob("fires-on-time", "/hooks/fire", runAt);

        awaitTrue(() -> executionRepository.countByJobId(jobId) == 1, Duration.ofSeconds(15),
                "expected exactly one execution record for job " + jobId);

        JobExecution execution = executionRepository.findByJobIdOrderByAttemptDesc(jobId).getFirst();

        assertThat(execution.getOutcome()).isEqualTo(ExecutionOutcome.SUCCEEDED);
        assertThat(execution.getResponseCode()).isEqualTo(200);
        assertThat(execution.getDriftMs())
                .as("drift measured by the scheduler itself")
                .isBetween(0L, 500L);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getLockedBy()).as("lease must be released on completion").isNull();
        assertThat(job.getLockExpiresAt()).isNull();
    }

    /**
     * The receiver gets an idempotency key derived from the scheduled time.
     *
     * <p>Phase 4 is where this earns its keep — every retry of one firing has to repeat this exact
     * string. Pinning the derivation now means the retry path is built against a key that already
     * has the right shape, rather than one that happens to work until the first failure.
     */
    @Test
    void deliveryCarriesAnIdempotencyKeyDerivedFromTheScheduledTime() {
        receiver.stubFor(post(urlEqualTo("/hooks/key")).willReturn(aResponse().withStatus(202)));
        Instant runAt = Instant.now().plusSeconds(1);

        String jobId = createJob("key-check", "/hooks/key", runAt);

        awaitTrue(() -> !receiver.findAll(postRequestedFor(urlEqualTo("/hooks/key"))).isEmpty(),
                Duration.ofSeconds(15), "receiver never got the webhook");

        List<LoggedRequest> requests = receiver.findAll(postRequestedFor(urlEqualTo("/hooks/key")));
        assertThat(requests).hasSize(1);

        Job job = jobRepository.findById(jobId).orElseThrow();
        String delivered = requests.getFirst().getHeader("X-Idempotency-Key");

        assertThat(delivered).isEqualTo(job.idempotencyKey());
        assertThat(delivered)
                .as("key must be anchored to currentRunScheduledFor, which is stable across retries")
                .endsWith("_run_" + job.getCurrentRunScheduledFor().toEpochMilli());
    }

    /**
     * A rejected delivery is recorded with the code the receiver actually returned.
     *
     * <p>Updated in Phase 4. This previously asserted that a 500 left the job {@code FAILED},
     * which was correct only while there was no retry path — a 500 is now classified as transient
     * and the job is rescheduled instead. The retry and dead-letter semantics themselves live in
     * {@code RetryAndDeadLetterTest}; what matters here is that the execution record faithfully
     * captures the outcome.
     */
    @Test
    void aRejectedDeliveryIsRecordedWithItsResponseCode() {
        receiver.stubFor(post(urlEqualTo("/hooks/bad")).willReturn(aResponse().withStatus(500)));

        String jobId = createJob("fails", "/hooks/bad", Instant.now().plusSeconds(1));

        awaitTrue(() -> executionRepository.countByJobId(jobId) >= 1, Duration.ofSeconds(15),
                "expected an execution record for the failed delivery");

        JobExecution first = executionRepository.findByJobIdOrderByAttemptDesc(jobId).stream()
                .min(java.util.Comparator.comparingInt(JobExecution::getAttempt))
                .orElseThrow();

        assertThat(first.getOutcome()).isEqualTo(ExecutionOutcome.FAILED);
        assertThat(first.getResponseCode()).isEqualTo(500);
        assertThat(first.getAttempt()).isEqualTo(1);

        // Not SUCCEEDED, and not prematurely terminal either: a transient failure keeps its
        // remaining budget rather than being written off on the first bad response.
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isNotEqualTo(JobStatus.SUCCEEDED);
    }

    /**
     * Drift is exported as histogram buckets, not as pre-computed percentiles.
     *
     * <p>This is the assertion that protects the project's headline number. Percentile gauges look
     * right on one worker and cannot be aggregated across several — the mean of three workers' p99
     * is not the fleet p99 — so the failure only appears once the architecture succeeds at the
     * thing it exists to demonstrate. Checking for {@code le} labels catches a regression to
     * {@code publishPercentiles} immediately.
     */
    @Test
    void driftIsExportedAsPrometheusBuckets() {
        receiver.stubFor(post(urlEqualTo("/hooks/metrics")).willReturn(aResponse().withStatus(200)));
        String jobId = createJob("metrics", "/hooks/metrics", Instant.now().plusSeconds(1));

        awaitTrue(() -> executionRepository.countByJobId(jobId) == 1, Duration.ofSeconds(15),
                "job never fired, so there is no drift to export");

        ResponseEntity<String> scrape = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = scrape.getBody();
        assertThat(body).isNotNull();
        assertThat(body)
                .as("drift must be published as cumulative buckets so Prometheus can aggregate p99 across workers")
                .contains("scheduler_drift_seconds_bucket")
                .contains("le=");
        assertThat(body).contains("scheduler_delivery_total");
        assertThat(body).contains("scheduler_jobs_due_depth");
    }

    // ---------------------------------------------------------------- helpers

    private String createJob(String name, String path, Instant runAt) {
        Map<String, Object> request = Map.of(
                "name", name,
                "schedule", Map.of("type", "ONE_TIME", "runAt", runAt.toString()),
                "target", Map.of(
                        "url", receiver.baseUrl() + path,
                        "method", "POST",
                        "payload", Map.of("n", 1)));

        ResponseEntity<Map> created = rest.postForEntity("/v1/jobs", request, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    /** Polls a condition rather than sleeping a fixed amount, so a fast machine isn't punished. */
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
