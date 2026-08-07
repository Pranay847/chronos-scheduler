package dev.pranay.chronos;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Liveness and readiness answer different questions, and conflating them is how an orchestrator
 * kills a working fleet.
 *
 * <p><strong>liveness</strong> means "restart me". It must depend on nothing external. If a shared
 * dependency appeared in this group, one database blip would fail the probe on every replica
 * simultaneously, Kubernetes would kill all of them, and each replacement would fail the identical
 * probe against the identical unavailable dependency — an outage manufactured by the health check.
 *
 * <p><strong>readiness</strong> means "send me traffic", so a dependency the pod cannot serve
 * without belongs here. Losing readiness removes the pod from the Service; it does not restart it,
 * and it does not stop the poller from retrying. The pod rejoins when the dependency returns.
 *
 * <p>The assertion that carries the weight is {@link #livenessDoesNotDependOnMongo()}. Everything
 * else here is structure; that one is the invariant, and it is checkable without a broken Mongo
 * because Boot reports which indicators compose each group.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "chronos.poller.enabled=false",
        "chronos.reaper.enabled=false"
})
class HealthProbesTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void livenessDoesNotDependOnMongo() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health/liveness", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");

        // The point of the whole split: no external dependency may appear here. A "mongo" key would
        // mean a database outage restarts every replica in the fleet at once.
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        if (components != null) {
            assertThat(components).doesNotContainKey("mongo");
            assertThat(components).doesNotContainKey("diskSpace");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessDoesDependOnMongo() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health/readiness", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");

        // A worker that cannot reach Mongo can neither claim nor record, so it should not be in the
        // Service. This is the group where that dependency is correct.
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertThat(components).containsKey("mongo");
    }

    @Test
    void bothProbeEndpointsExistWithoutKubernetesAutoDetection() {
        // Boot enables these automatically only when it believes it is running on Kubernetes. Under
        // Testcontainers it does not, so without the explicit probes.enabled property these would
        // 404 here and appear in the cluster -- untestable exactly where they matter.
        assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
