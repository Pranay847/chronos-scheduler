package dev.pranay.chronos;

import dev.pranay.chronos.config.MongoPingHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health endpoint is load-bearing infrastructure, not decoration: hosting platforms use it to
 * decide whether an instance ever receives traffic. A health check that reports DOWN on a working
 * application is therefore an outage, and this test exists because that outage actually happened —
 * Boot's auto-configured Mongo indicator probes {@code hello} against the {@code local} database,
 * Atlas grants application users no privileges there, and a fully functional deployment sat failing
 * its readiness probe until the platform gave up on it.
 *
 * <p>Testcontainers cannot reproduce the Atlas permission model — the container's root user can read
 * {@code local} quite happily, which is precisely why the bug reached a real deployment before
 * anything caught it. So these assertions pin the two things that are checkable here and that would
 * each independently reintroduce the failure: that the built-in indicator is not the one wired in,
 * and that the replacement queries the application's own database rather than {@code local}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MongoHealthIndicatorTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MongoPingHealthIndicator indicator;

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Test
    void reportsUpAgainstTheApplicationDatabaseRatherThanLocal() {
        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        // Asserted against the template's own database name rather than a literal, because that is
        // the actual invariant: whatever database the application is configured for is the one the
        // probe must hit. Hardcoding "chronos" would only pin the production profile and fail here,
        // where Testcontainers hands us "test".
        assertThat(health.getDetails())
                .containsEntry("database", mongoTemplate.getDb().getName())
                .containsEntry("ok", 1.0);
        // The regression that broke the Atlas deployment, stated directly.
        assertThat(health.getDetails().get("database")).isNotEqualTo("local");
    }

    @Test
    void bootsAutoConfiguredMongoIndicatorIsNotRegistered() {
        Map<String, HealthIndicator> indicators = context.getBeansOfType(HealthIndicator.class);

        // Ours must be the only thing answering for Mongo. If Boot's returns -- because someone drops
        // management.health.mongo.enabled=false -- the deployment breaks again on Atlas and nowhere
        // else, which is the hardest kind of regression to attribute.
        assertThat(indicators.values())
                .filteredOn(i -> i.getClass().getName().startsWith("org.springframework.boot")
                        && i.getClass().getSimpleName().contains("Mongo"))
                .isEmpty();
        // Boot strips the HealthIndicator suffix, so this bean name is what puts it under "mongo"
        // in the health response.
        assertThat(indicators).containsKey("mongoHealthIndicator");
        assertThat(indicators.get("mongoHealthIndicator")).isInstanceOf(MongoPingHealthIndicator.class);
    }
}
