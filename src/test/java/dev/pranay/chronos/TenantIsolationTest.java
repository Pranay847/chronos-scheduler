package dev.pranay.chronos;

import dev.pranay.chronos.repository.JobRepository;
import dev.pranay.chronos.repository.TenantRepository;
import dev.pranay.chronos.security.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication and tenant isolation, with the security switches at their <em>production</em>
 * settings.
 *
 * <p>The rest of the suite runs in single-tenant mode because it predates authentication. This
 * class turns the real behaviour back on — which is the only way any of these assertions mean
 * anything.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "chronos.security.require-api-key=true",
        // Targets stay permissive: these tests are about who can see what, not about where
        // deliveries go, and the SSRF guard has its own suite.
        "chronos.security.allow-private-targets=true",
        "chronos.poller.enabled=false",
        "chronos.reaper.enabled=false"
})
class TenantIsolationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private ApiKeyService.IssuedKey alice;
    private ApiKeyService.IssuedKey bob;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        tenantRepository.deleteAll();
        alice = apiKeyService.createTenant("alice-corp");
        bob = apiKeyService.createTenant("bob-industries");
    }

    /**
     * <b>The assertion this whole phase is for.</b>
     *
     * <p>Tenant A cannot read tenant B's job — not "gets a 403", but cannot tell it exists at all.
     * Every query is scoped by tenant id, so a cross-tenant lookup returns nothing rather than
     * finding something and then deciding to refuse it.
     */
    @Test
    void oneTenantCannotReadAnothersJob() {
        String aliceJob = createJob(alice, "alice-secret-job");

        ResponseEntity<String> asBob = get("/v1/jobs/" + aliceJob, bob);

        assertThat(asBob.getStatusCode())
                .as("404, not 403 — confirming existence would itself leak that the job is real")
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(get("/v1/jobs/" + aliceJob, alice).getStatusCode())
                .as("and Alice can still read her own")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void oneTenantCannotCancelPauseOrTriggerAnothersJob() {
        String aliceJob = createJob(alice, "alice-job");

        assertThat(post("/v1/jobs/" + aliceJob + "/pause", bob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/v1/jobs/" + aliceJob + "/trigger", bob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/jobs/" + aliceJob, HttpMethod.DELETE, bob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(jobRepository.findById(aliceJob).orElseThrow().getStatus().name())
                .as("none of Bob's attempts may have changed Alice's job")
                .isEqualTo("PENDING");
    }

    @Test
    void requestsWithoutAKeyAreRejected() {
        assertThat(rest.getForEntity("/v1/jobs/anything", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A bad key, a malformed header and no key at all are indistinguishable from outside.
     *
     * <p>Different responses would let someone probe which keys are real, turning a guess into a
     * search.
     */
    @Test
    void invalidAndMissingKeysAreIndistinguishable() {
        HttpHeaders bogus = new HttpHeaders();
        bogus.setBearerAuth("chr_not-a-real-key");
        ResponseEntity<String> withBadKey =
                rest.exchange("/v1/jobs/anything", HttpMethod.GET, new HttpEntity<>(bogus), String.class);

        HttpHeaders malformed = new HttpHeaders();
        malformed.set(HttpHeaders.AUTHORIZATION, "NotBearer whatever");
        ResponseEntity<String> withMalformed =
                rest.exchange("/v1/jobs/anything", HttpMethod.GET, new HttpEntity<>(malformed), String.class);

        assertThat(withBadKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withMalformed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Only the hash is stored, so a database dump yields nothing usable. */
    @Test
    void theApiKeyIsNeverStoredInPlaintext() {
        var stored = tenantRepository.findById(alice.tenantId()).orElseThrow();

        assertThat(stored.getApiKeyHash()).isNotEqualTo(alice.apiKey());
        assertThat(stored.getApiKeyHash()).doesNotContain(alice.apiKey());
        assertThat(tenantRepository.findAll())
                .as("no document anywhere may contain the plaintext key")
                .noneSatisfy(t -> assertThat(t.getApiKeyHash()).isEqualTo(alice.apiKey()));
    }

    @Test
    void actuatorAndTenantCreationDoNotRequireAKey() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> created = rest.postForEntity(
                "/v1/tenants", Map.of("name", "bootstrap-corp"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * Rotation keeps the previous secret valid, capped at two.
     *
     * <p>Rotation that invalidates the old secret immediately breaks every consumer the moment you
     * press it — which in practice means nobody ever rotates, and the feature is decorative.
     */
    @Test
    void secretRotationKeepsThePreviousSecretAndCapsTheList() {
        String original = tenantRepository.findById(alice.tenantId()).orElseThrow().primarySecret();

        String second = apiKeyService.rotateSigningSecret(alice.tenantId());
        var afterFirst = tenantRepository.findById(alice.tenantId()).orElseThrow();

        assertThat(afterFirst.primarySecret()).isEqualTo(second);
        assertThat(afterFirst.getSigningSecrets()).hasSize(2);
        assertThat(afterFirst.getSigningSecrets().get(1).secret())
                .as("the previous secret must still verify during the rotation window")
                .isEqualTo(original);

        apiKeyService.rotateSigningSecret(alice.tenantId());
        assertThat(tenantRepository.findById(alice.tenantId()).orElseThrow().getSigningSecrets())
                .as("capped at two — an unbounded list widens the window a leaked secret stays valid")
                .hasSize(2);
    }

    // ---------------------------------------------------------------- helpers

    private String createJob(ApiKeyService.IssuedKey as, String name) {
        Map<String, Object> body = Map.of(
                "name", name,
                "schedule", Map.of("type", "ONE_TIME", "runAt", Instant.now().plusSeconds(3600).toString()),
                "target", Map.of("url", "http://127.0.0.1:9999/hook", "method", "POST"));

        ResponseEntity<Map> created = rest.exchange("/v1/jobs", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(as)), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private ResponseEntity<String> get(String path, ApiKeyService.IssuedKey as) {
        return exchange(path, HttpMethod.GET, as);
    }

    private ResponseEntity<String> post(String path, ApiKeyService.IssuedKey as) {
        return exchange(path, HttpMethod.POST, as);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, ApiKeyService.IssuedKey as) {
        return rest.exchange(path, method, new HttpEntity<>(authHeaders(as)), String.class);
    }

    private static HttpHeaders authHeaders(ApiKeyService.IssuedKey as) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(as.apiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
