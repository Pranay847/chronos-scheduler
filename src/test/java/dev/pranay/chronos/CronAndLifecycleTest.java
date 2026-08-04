package dev.pranay.chronos;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.pranay.chronos.domain.Job;
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
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recurring jobs end to end, plus the lifecycle endpoints.
 *
 * <p>The poller is on: a cron job that re-arms itself is only interesting if something is actually
 * claiming it again afterwards.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "chronos.reaper.enabled=false")
class CronAndLifecycleTest {

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
     * Delete every job the test created, without exception.
     *
     * <p>Not tidiness — necessity. Spring caches application contexts across test classes, so this
     * class's poller keeps running long after its last test finishes. A recurring job re-arms
     * itself by design, so one left behind fires forever: against a WireMock that {@code @AfterAll}
     * has already stopped, generating failures and retries that slow every subsequent test class
     * and pollute the shared database.
     *
     * <p>Which is the flip side of recurrence being "just a job that reschedules itself" — nothing
     * ever stops it on its own.
     */
    @org.junit.jupiter.api.AfterEach
    void removeRecurringJobs() {
        jobRepository.deleteAll();
        executionRepository.deleteAll();
    }

    /**
     * A cron job re-arms itself instead of reaching a terminal state.
     *
     * <p>Recurrence is not a separate loop anywhere in the system — it is a job that puts itself
     * back in PENDING with a new scheduled time, so it inherits claiming, leasing and crash
     * recovery unchanged.
     */
    @Test
    void aCronJobFiresRepeatedlyAndKeepsRescheduling() {
        receiver.stubFor(post(urlEqualTo("/tick")).willReturn(aResponse().withStatus(200)));

        // Every second, so the test observes several firings without waiting on the clock.
        String jobId = createCronJob("ticker", "/tick", "* * * * * *", "UTC");

        awaitTrue(() -> executionRepository.countByJobId(jobId) >= 3, Duration.ofSeconds(30),
                "cron job did not fire repeatedly");

        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/tick"))).size()).isGreaterThanOrEqualTo(3);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus())
                .as("a recurring job never reaches SUCCEEDED — it goes back to PENDING")
                .isIn(JobStatus.PENDING, JobStatus.CLAIMED);
        assertThat(job.getNextRunAt()).isAfter(job.getCreatedAt());
    }

    /**
     * Each occurrence is a distinct firing with its own identity.
     *
     * <p>The mirror image of the retry rule. A retry must reuse the key because it is the same
     * firing; an occurrence must not, because tomorrow's run is a different event. Sharing keys
     * across occurrences would be worse than useless — a receiver deduplicating on it would drop
     * every run after the first, forever.
     */
    @Test
    void eachOccurrenceGetsItsOwnIdempotencyKey() {
        receiver.stubFor(post(urlEqualTo("/tick")).willReturn(aResponse().withStatus(200)));
        String jobId = createCronJob("distinct-keys", "/tick", "* * * * * *", "UTC");

        awaitTrue(() -> executionRepository.countByJobId(jobId) >= 3, Duration.ofSeconds(30),
                "cron job did not fire enough times");

        var keys = executionRepository.findByJobIdOrderByAttemptDesc(jobId).stream()
                .map(e -> e.getIdempotencyKey())
                .distinct()
                .toList();

        assertThat(keys)
                .as("each occurrence is a separate event and must be separately identifiable")
                .hasSizeGreaterThanOrEqualTo(3);
    }

    /** A recurring job's attempt budget resets on each occurrence, rather than accumulating. */
    @Test
    void rollingForwardResetsTheAttemptBudget() {
        receiver.stubFor(post(urlEqualTo("/tick")).willReturn(aResponse().withStatus(200)));
        String jobId = createCronJob("budget-reset", "/tick", "* * * * * *", "UTC");

        awaitTrue(() -> executionRepository.countByJobId(jobId) >= 2, Duration.ofSeconds(30),
                "cron job did not fire twice");

        assertThat(executionRepository.findByJobIdOrderByAttemptDesc(jobId))
                .as("every occurrence succeeded first time, so every record is attempt 1")
                .allSatisfy(e -> assertThat(e.getAttempt()).isEqualTo(1));
    }

    @Test
    void anInvalidCronExpressionIsRejectedAtCreation() {
        Map<String, Object> request = Map.of(
                "name", "bad-cron",
                "schedule", Map.of("type", "CRON", "cronExpression", "not a cron", "timezone", "UTC"),
                "target", Map.of("url", receiver.baseUrl() + "/tick"));

        ResponseEntity<String> response = rest.postForEntity("/v1/jobs", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("cron expression");
    }

    @Test
    void anUnknownTimezoneIsRejectedAtCreation() {
        Map<String, Object> request = Map.of(
                "name", "bad-zone",
                "schedule", Map.of("type", "CRON", "cronExpression", "0 0 9 * * *", "timezone", "Mars/Olympus"),
                "target", Map.of("url", receiver.baseUrl() + "/tick"));

        assertThat(rest.postForEntity("/v1/jobs", request, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void pauseTakesAJobOutOfCirculationAndResumePutsItBack() {
        receiver.stubFor(post(urlEqualTo("/tick")).willReturn(aResponse().withStatus(200)));
        String jobId = createFutureJob("pausable", "/tick");

        ResponseEntity<Map> paused = rest.postForEntity("/v1/jobs/" + jobId + "/pause", null, Map.class);
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.PAUSED);

        ResponseEntity<Map> resumed = rest.postForEntity("/v1/jobs/" + jobId + "/resume", null, Map.class);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    /**
     * Triggering a recurring job must not re-base its schedule.
     *
     * <p>The one-line implementation — set {@code nextRunAt = now} — silently converts a daily
     * 09:00 job into a daily "whenever someone last clicked trigger" job, because the rollover
     * computes the next occurrence from the scheduled time. Firing manually therefore creates a
     * <em>separate</em> one-time job instead.
     */
    @Test
    void triggerFiresNowWithoutDisturbingTheOriginalSchedule() {
        receiver.stubFor(post(urlEqualTo("/tick")).willReturn(aResponse().withStatus(200)));

        // Daily at 09:00, so its next run is far away and any re-basing would be obvious.
        String jobId = createCronJob("daily-report", "/tick", "0 0 9 * * *", "America/Chicago");
        Job before = jobRepository.findById(jobId).orElseThrow();

        ResponseEntity<Map> triggered = rest.postForEntity("/v1/jobs/" + jobId + "/trigger", null, Map.class);

        assertThat(triggered.getStatusCode())
                .as("a manual fire creates a new job, so 201 rather than 200")
                .isEqualTo(HttpStatus.CREATED);

        String manualId = (String) triggered.getBody().get("id");
        assertThat(manualId).isNotEqualTo(jobId);

        Job after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getNextRunAt())
                .as("the original schedule must be untouched")
                .isEqualTo(before.getNextRunAt());
        assertThat(after.getCurrentRunScheduledFor()).isEqualTo(before.getCurrentRunScheduledFor());

        Job manual = jobRepository.findById(manualId).orElseThrow();
        assertThat(manual.getSchedule().isCron()).as("the manual copy is one-time, not recurring").isFalse();
        assertThat(manual.getTriggeredFrom()).isEqualTo(jobId);
        assertThat(manual.getTarget().url()).isEqualTo(before.getTarget().url());

        awaitTrue(() -> executionRepository.countByJobId(manualId) == 1, Duration.ofSeconds(20),
                "the manually triggered job never fired");
    }

    @Test
    void cancelStopsAJobPermanently() {
        String jobId = createFutureJob("cancel-me", "/tick");

        ResponseEntity<Map> cancelled = rest.exchange(
                "/v1/jobs/" + jobId, org.springframework.http.HttpMethod.DELETE, null, Map.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void aClaimedJobCannotBePaused() {
        // Already-terminal states are the easy case; this asserts the transition guard exists.
        String jobId = createFutureJob("guarded", "/tick");
        rest.exchange("/v1/jobs/" + jobId, org.springframework.http.HttpMethod.DELETE, null, Map.class);

        ResponseEntity<String> response = rest.postForEntity("/v1/jobs/" + jobId + "/pause", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("PENDING");
    }

    // ---------------------------------------------------------------- helpers

    private String createCronJob(String name, String path, String expression, String zone) {
        Map<String, Object> request = Map.of(
                "name", name,
                "schedule", Map.of("type", "CRON", "cronExpression", expression, "timezone", zone),
                "target", Map.of("url", receiver.baseUrl() + path, "method", "POST", "payload", Map.of("n", 1)));
        return createJob(request);
    }

    private String createFutureJob(String name, String path) {
        Map<String, Object> request = Map.of(
                "name", name,
                "schedule", Map.of("type", "ONE_TIME", "runAt", Instant.now().plusSeconds(3600).toString()),
                "target", Map.of("url", receiver.baseUrl() + path, "method", "POST", "payload", Map.of("n", 1)));
        return createJob(request);
    }

    private String createJob(Map<String, Object> request) {
        ResponseEntity<Map> created = rest.postForEntity("/v1/jobs", request, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
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
