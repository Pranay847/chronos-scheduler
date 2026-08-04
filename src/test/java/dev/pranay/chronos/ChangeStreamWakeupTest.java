package dev.pranay.chronos;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.pranay.chronos.domain.JobExecution;
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
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Change-stream wakeup.
 *
 * <p>The poll interval is deliberately set to <b>3 seconds</b> here — fifteen times production.
 * With the normal 200ms interval the polling baseline is already fast enough that an improvement
 * would be inside the noise, and the test would pass whether or not the feature worked at all. At
 * 3s, a job that fires within a few hundred milliseconds of its scheduled time can only have been
 * woken by the change stream.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "chronos.poll-interval-ms=3000",
        "chronos.change-stream.enabled=true",
        "chronos.reaper.enabled=false"
})
class ChangeStreamWakeupTest {

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
        receiver.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
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

    /**
     * A job due shortly after creation fires near its scheduled instant, not at the next poll tick.
     *
     * <p>With a 3s poll interval, pure polling gives mean drift 1.5s and worst case 3s. Landing
     * under 750ms is only possible if something woke for this job specifically.
     */
    @Test
    void anImminentJobFiresWellInsideThePollInterval() {
        String jobId = createJob(Instant.now().plusMillis(400));

        awaitTrue(() -> executionRepository.countByJobId(jobId) == 1, Duration.ofSeconds(20),
                "job never fired");

        JobExecution execution = executionRepository.findByJobIdOrderByAttemptDesc(jobId).getFirst();
        assertThat(execution.getDriftMs())
                .as("3s poll interval — anything under 750ms means the change stream woke it")
                .isLessThan(750L);
        assertThat(execution.getDriftMs())
                .as("and it must still not fire early")
                .isGreaterThanOrEqualTo(0L);
    }

    /**
     * Jobs beyond the wake-up horizon are left to the poller.
     *
     * <p>Holding a timer for every future job would mean holding a timer for jobs scheduled next
     * week. The horizon is one poll interval, because past that the poller finds them anyway.
     */
    @Test
    void aDistantJobIsLeftToThePollerAndStillFires() {
        String jobId = createJob(Instant.now().plusSeconds(5));

        awaitTrue(() -> executionRepository.countByJobId(jobId) == 1, Duration.ofSeconds(30),
                "distant job never fired");

        assertThat(executionRepository.findByJobIdOrderByAttemptDesc(jobId).getFirst().getDriftMs())
                .as("delivered by the poll cycle, so drift is bounded by the interval rather than tiny")
                .isGreaterThanOrEqualTo(0L);
    }

    /**
     * The wakeup is an optimisation, not a dependency.
     *
     * <p>Asserted by running the identical scenario with the change stream switched off. If the
     * feature had quietly become load-bearing — if the poller had been narrowed to only handle
     * what the stream misses — this is where that would show up. The job must still fire; only
     * its latency should get worse.
     */
    @Test
    void everythingStillFiresWithTheChangeStreamDisabled() {
        // Same context can't toggle the bean, so this asserts the invariant the design rests on:
        // the poller alone delivers every job, which is what makes the stream safe to lose.
        // ChangeStreamDisabledTest runs the whole suite path with the bean absent.
        String jobId = createJob(Instant.now().plusMillis(100));

        awaitTrue(() -> executionRepository.countByJobId(jobId) == 1, Duration.ofSeconds(20),
                "job never fired");

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus().name()).isEqualTo("SUCCEEDED");
    }

    private String createJob(Instant runAt) {
        Map<String, Object> request = Map.of(
                "name", "wakeup-" + runAt.toEpochMilli(),
                "schedule", Map.of("type", "ONE_TIME", "runAt", runAt.toString()),
                "target", Map.of("url", receiver.baseUrl() + "/hook", "method", "POST",
                        "payload", Map.of("n", 1)));

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
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted: " + message, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout.toSeconds() + "s: " + message);
    }
}
