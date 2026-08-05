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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The root URL serves a static landing page rather than a 401.
 *
 * <p>An API with no UI answers a browser with a problem document, which is correct and useless to a
 * human who was sent a link. The fix is a static page and a filter exemption for it — and the
 * exemption is the part worth testing, because widening an auth filter is exactly the kind of change
 * that quietly widens it too far.
 *
 * <p>So the assertions come in pairs: the page is reachable without a credential, <em>and</em> every
 * API path still is not. The second half is the one that matters. A prefix match on {@code "/"}
 * instead of an exact match would satisfy the first assertion and disable authentication entirely.
 *
 * <p>Runs with {@code require-api-key=true}, since a test of authentication behaviour against a
 * build with authentication switched off asserts nothing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "chronos.security.require-api-key=true",
        "chronos.poller.enabled=false",
        "chronos.reaper.enabled=false"
})
class LandingPageTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void rootServesTheLandingPageWithoutACredential() {
        ResponseEntity<String> response = rest.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<title>Chronos")
                .contains("distributed job");
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/html");
    }

    @Test
    void theLandingPageExemptionDoesNotOpenUpTheApi() {
        // Every one of these would return 200 if "/" were treated as a prefix rather than an exact
        // path. That mistake is silent -- the landing page works, so the change looks correct.
        assertThat(rest.getForEntity("/v1/jobs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/v1/jobs/anything", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/v1/dead-letters", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unauthorizedResponsesAreStillProblemJsonNotHtml() {
        // The landing page must not become the error page. A browser hitting a protected endpoint
        // should still get a machine-readable 401, because API clients outnumber humans here.
        ResponseEntity<String> response = rest.getForEntity("/v1/jobs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(response.getBody()).contains("\"status\":401");
    }
}
