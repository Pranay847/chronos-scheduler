package dev.pranay.chronos;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.pranay.chronos.repository.JobExecutionRepository;
import dev.pranay.chronos.repository.JobRepository;
import dev.pranay.chronos.scheduler.ChangeStreamWakeup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
 * The same scenario with the change stream absent entirely.
 *
 * <p>This is the test that keeps the optimisation honest. It is easy for a latency feature to
 * become load-bearing by accident — a filter narrowed here, a claim path shortened there — and the
 * result is a system that appears to work until the oplog connection drops and jobs silently stop
 * firing. Running the identical path with the bean gone proves the poller alone is still a complete
 * scheduler.
 *
 * <p>The bean is removed rather than mocked, so a compile-time dependency on it would fail here
 * too.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "chronos.poll-interval-ms=500",
        "chronos.change-stream.enabled=false",
        "chronos.reaper.enabled=false"
})
class ChangeStreamDisabledTest {

    private static WireMockServer receiver;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext context;

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

    @Test
    void theWakeupBeanIsGenuinelyAbsent() {
        assertThat(context.getBeanNamesForType(ChangeStreamWakeup.class))
                .as("the property must actually remove it, or the next assertion proves nothing")
                .isEmpty();
    }

    @Test
    void everyJobStillFiresOnThePollerAlone() {
        String a = createJob(Instant.now().plusMillis(100));
        String b = createJob(Instant.now().plusSeconds(2));

        awaitTrue(() -> executionRepository.countByJobId(a) == 1
                        && executionRepository.countByJobId(b) == 1,
                Duration.ofSeconds(30), "polling did not deliver both jobs");

        assertThat(jobRepository.findById(a).orElseThrow().getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(jobRepository.findById(b).orElseThrow().getStatus().name()).isEqualTo("SUCCEEDED");

        // Latency is worse without the wakeup — that is the entire trade — but correctness is not.
        assertThat(executionRepository.findByJobIdOrderByAttemptDesc(a).getFirst().getDriftMs())
                .isGreaterThanOrEqualTo(0L);
    }

    private String createJob(Instant runAt) {
        Map<String, Object> request = Map.of(
                "name", "polled-" + runAt.toEpochMilli(),
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
