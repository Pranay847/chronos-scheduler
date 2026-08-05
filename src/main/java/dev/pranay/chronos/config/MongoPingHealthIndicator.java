package dev.pranay.chronos.config;

import org.bson.Document;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Replaces Boot's auto-configured {@code MongoHealthIndicator}, which is unusable on a hosted
 * cluster with least-privilege credentials.
 *
 * <p>The built-in indicator issues {@code hello} against the {@code local} database. That is a
 * reasonable choice against a mongod you own — {@code local} holds the oplog and replica set state,
 * so it answers "is this a healthy replica set member" rather than merely "is the socket open".
 * It is the wrong choice against MongoDB Atlas, which reserves {@code local} and grants application
 * users no privileges on it at any tier. The result on Atlas M0:
 *
 * <pre>
 * MongoCommandException: error 8000 (AtlasError): (Unauthorized) not authorized on local
 *                        to execute command { hello: 1, $db: "local" }
 * </pre>
 *
 * <p>The failure mode is worse than it looks. The application is entirely healthy — it connects,
 * claims, and delivers — but {@code /actuator/health} reports DOWN forever, so any platform using
 * that endpoint as a readiness probe (Render, Kubernetes, App Runner) kills or never releases the
 * instance. A broken liveness signal takes down a working service, which is exactly the class of
 * outage health checks are supposed to prevent.
 *
 * <p>{@code ping} is the fix: it is the one command MongoDB explicitly documents as requiring no
 * authorization, and {@link MongoTemplate#executeCommand(Document)} runs it against the configured
 * application database rather than {@code local}. That still proves what a health check needs to
 * prove — credentials are valid, the driver has selected a server, and it answers — while working
 * identically on Atlas, on a local replica set, and under Testcontainers.
 *
 * <p>The bean is named {@code mongoHealthIndicator}, not {@code mongo}: Boot derives the health
 * response key by stripping the {@code HealthIndicator} suffix, so this still reports under
 * {@code "mongo"} — while {@code "mongo"} as a bean name is already taken by the {@code MongoClient}
 * from {@code MongoAutoConfiguration} and claiming it fails the context outright. The built-in
 * indicator is switched off in {@code application.properties}, so nothing else competes for the key.
 */
@Component("mongoHealthIndicator")
public class MongoPingHealthIndicator implements HealthIndicator {

    private final MongoTemplate mongoTemplate;

    public MongoPingHealthIndicator(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Health health() {
        try {
            Document result = mongoTemplate.executeCommand(new Document("ping", 1));
            return Health.up()
                    .withDetail("database", mongoTemplate.getDb().getName())
                    .withDetail("ok", result.get("ok"))
                    .build();
        } catch (Exception ex) {
            // Down rather than propagating: an exception out of a health indicator becomes a 500
            // with a stack trace, which tells a probe less than a structured DOWN does.
            return Health.down(ex).build();
        }
    }
}
