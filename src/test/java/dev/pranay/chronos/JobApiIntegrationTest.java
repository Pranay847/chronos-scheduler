package dev.pranay.chronos;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import dev.pranay.chronos.api.dto.JobResponse;
import dev.pranay.chronos.domain.JobStatus;
import dev.pranay.chronos.domain.ScheduleType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 acceptance.
 *
 * <p>The headline case is create-then-read-back, but most of what's here guards decisions that
 * are cheap to make correctly now and expensive to discover later: that Mongo really is a
 * replica set, that job writes really are majority-acknowledged, and that the claim index really
 * is ordered equality-before-range. Each of those is invisible in normal operation and each one
 * silently invalidates a later phase.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class JobApiIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    // ---------------------------------------------------------------- the Phase 1 "done when"

    @Test
    void createsAJobAndReadsItBack() {
        Instant runAt = Instant.now().plus(10, ChronoUnit.MINUTES);

        ResponseEntity<JobResponse> created = rest.postForEntity("/v1/jobs", oneTimeJob("send-trial-expiry", runAt),
                JobResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();

        JobResponse body = created.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotBlank();
        assertThat(body.name()).isEqualTo("send-trial-expiry");
        assertThat(body.status()).isEqualTo(JobStatus.PENDING);
        assertThat(body.schedule().type()).isEqualTo(ScheduleType.ONE_TIME);

        ResponseEntity<JobResponse> fetched = rest.getForEntity("/v1/jobs/" + body.id(), JobResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(body.id());
        assertThat(fetched.getBody().target().url()).isEqualTo("https://example.com/hooks/trial");
    }

    /**
     * A fresh job's poll time and firing time start equal, and neither is null.
     *
     * <p>They diverge the moment a delivery fails — {@code nextRunAt} moves to
     * {@code now + backoff} while {@code currentRunScheduledFor} stays put, which is what keeps
     * every retry of one firing carrying the same idempotency key. That divergence is Phase 4's
     * to prove. What matters here is the precondition: if {@code currentRunScheduledFor} were
     * left null until the first successful run, the first firing would have no stable identity
     * and the first cron rollover would dereference null.
     */
    @Test
    void newJobsFiringTimeIsInitialisedAndMatchesItsPollTime() {
        Instant runAt = Instant.now().plus(5, ChronoUnit.MINUTES);

        JobResponse job = rest.postForEntity("/v1/jobs", oneTimeJob("identity-check", runAt), JobResponse.class)
                .getBody();

        assertThat(job).isNotNull();
        assertThat(job.currentRunScheduledFor()).isNotNull();
        assertThat(job.nextRunAt()).isNotNull();
        assertThat(job.currentRunScheduledFor()).isEqualTo(job.nextRunAt());
        assertThat(job.attempt()).isZero();
        assertThat(job.claimCount()).isZero();
    }

    // ---------------------------------------------------------------- infrastructure guarantees

    /**
     * Mongo is a replica set, not a standalone.
     *
     * <p>Cheap to assert and worth asserting, because the failure it catches is delayed and
     * misleading: a standalone server accepts every write in Phase 1 and only starts refusing
     * things in Phase 9, when change streams arrive and report "not supported on standalone" —
     * by which point the obvious suspect is the change-stream code rather than the container
     * setup. {@code setName} is only present on a replica set member.
     */
    @Test
    void mongoIsARealReplicaSet() {
        Document hello = mongoTemplate.getDb().runCommand(new Document("hello", 1));

        assertThat(hello.getString("setName"))
                .as("mongod must run as a replica set member; a standalone breaks change streams in Phase 9")
                .isNotBlank();
        assertThat(hello.getBoolean("isWritablePrimary", false))
                .as("the single member must have completed rs.initiate() and been elected primary")
                .isTrue();
    }

    /**
     * Job writes are majority-acknowledged.
     *
     * <p>The driver default is {@code w:1} — the primary alone, before replication. On this
     * single-node container that is indistinguishable from majority, which is exactly why the
     * mistake survives local development: it only becomes visible on a multi-node deployment,
     * where an acknowledged-but-unreplicated claim can vanish in a failover and let a second
     * worker claim the same job. Asserting the configuration is the only way to catch it before
     * Atlas does.
     *
     * <p>This reads the concern the collection <em>inherits from the client</em>, which is where
     * {@code MongoConfig} sets the floor. It is deliberately not an assertion about the template:
     * Spring Data applies template-level write concern per operation, inside
     * {@code doInsert}/{@code doUpdate}, while {@code prepareCollection} only ever applies
     * {@code readPreference} — so a collection handle taken from the template reports
     * {@code w=null} however the template is configured. Asserting on that handle and expecting
     * it to reflect template configuration is a trap; this test previously fell into it and
     * reported a config bug that did not exist.
     *
     * <p>What this does prove is the part that matters: no write reaching this cluster can
     * quietly fall back to {@code w:1}.
     */
    @Test
    void jobWritesUseMajorityWriteConcern() {
        WriteConcern effective = mongoTemplate.execute("jobs", MongoCollection::getWriteConcern);

        assertThat(effective)
                .as("job state must not fall back to the driver's w:1 default")
                .isEqualTo(WriteConcern.MAJORITY);
    }

    /**
     * The claim index exists and puts the equality field before the range field.
     *
     * <p>{@code status} is matched for equality and {@code nextRunAt} is a range scan plus the
     * sort. In that order one index serves filter and sort together. Reversed, Mongo has to walk
     * every future-dated document of every status to find the pending ones — the plan lists the
     * resulting COLLSCAN as a sharp edge, and it is also the comparison the Phase 8 benchmark
     * is built on. Index definitions are easy to reorder by accident during a refactor, and
     * nothing about the system's behaviour would look wrong afterwards; only its latency.
     */
    @Test
    void claimIndexOrdersEqualityBeforeRange() {
        List<IndexInfo> indexes = mongoTemplate.indexOps("jobs").getIndexInfo();

        IndexInfo claimIndex = indexes.stream()
                .filter(i -> "idx_claim".equals(i.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "idx_claim is missing; found " + indexes.stream().map(IndexInfo::getName).toList()));

        List<String> keyOrder = claimIndex.getIndexFields().stream()
                .map(f -> f.getKey())
                .toList();

        assertThat(keyOrder).containsExactly("status", "nextRunAt");
    }

    // ---------------------------------------------------------------- validation boundaries

    /**
     * A cron job is accepted with its first occurrence already computed.
     *
     * <p>Replaced the Phase 1 placeholder that asserted CRON was rejected. The important
     * assertion now is that {@code nextRunAt} and {@code currentRunScheduledFor} are both
     * populated at creation: leaving them until the first successful run means the very first
     * rollover dereferences null, and the first firing has no scheduled time to derive an
     * idempotency key from. Zone and DST behaviour are covered in {@code CronCalculatorTest}.
     */
    @Test
    void cronSchedulesAreAcceptedWithTheirFirstOccurrenceResolved() {
        Map<String, Object> request = Map.of(
                "name", "nightly-report",
                "schedule", Map.of("type", "CRON", "cronExpression", "0 0 9 * * *", "timezone", "America/Chicago"),
                "target", Map.of("url", "https://example.com/hooks/nightly"));

        ResponseEntity<JobResponse> response = rest.postForEntity("/v1/jobs", request, JobResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JobResponse job = response.getBody();
        assertThat(job).isNotNull();
        assertThat(job.schedule().type()).isEqualTo(ScheduleType.CRON);
        assertThat(job.schedule().timezone()).isEqualTo("America/Chicago");
        assertThat(job.nextRunAt())
                .as("the first occurrence must be resolved at creation, not on first run")
                .isNotNull()
                .isAfter(Instant.now());
        assertThat(job.currentRunScheduledFor()).isEqualTo(job.nextRunAt());
    }

    @Test
    void aCronJobMayNotAlsoSpecifyARunAt() {
        Map<String, Object> request = Map.of(
                "name", "confused",
                "schedule", Map.of("type", "CRON", "cronExpression", "0 0 9 * * *",
                        "runAt", Instant.now().toString()),
                "target", Map.of("url", "https://example.com/hooks/x"));

        ResponseEntity<String> response = rest.postForEntity("/v1/jobs", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("runAt");
    }

    @Test
    void oneTimeJobsWithoutARunAtAreRejected() {
        Map<String, Object> request = Map.of(
                "name", "no-time",
                "schedule", Map.of("type", "ONE_TIME"),
                "target", Map.of("url", "https://example.com/hooks/x"));

        ResponseEntity<String> response = rest.postForEntity("/v1/jobs", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("runAt");
    }

    @Test
    void nonHttpTargetsAreRejected() {
        Map<String, Object> request = Map.of(
                "name", "bad-scheme",
                "schedule", Map.of("type", "ONE_TIME", "runAt", Instant.now().toString()),
                "target", Map.of("url", "file:///etc/passwd"));

        ResponseEntity<String> response = rest.postForEntity("/v1/jobs", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Payloads are capped at creation.
     *
     * <p>Mongo's document limit is 16MB and a dead-lettered job snapshots the whole document,
     * so an unbounded payload is charged twice. Rejecting at creation beats failing deep in the
     * driver on the first delivery attempt, months later.
     */
    @Test
    void oversizedPayloadsAreRejected() {
        String big = "x".repeat(300_000);
        Map<String, Object> request = Map.of(
                "name", "too-big",
                "schedule", Map.of("type", "ONE_TIME", "runAt", Instant.now().toString()),
                "target", Map.of("url", "https://example.com/hooks/x", "payload", Map.of("blob", big)));

        ResponseEntity<String> response = rest.postForEntity("/v1/jobs", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("limit is");
    }

    @Test
    void unknownJobReturnsProblemJson() {
        ResponseEntity<String> response = rest.getForEntity("/v1/jobs/507f1f77bcf86cd799439011", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> oneTimeJob(String name, Instant runAt) {
        return Map.of(
                "name", name,
                "schedule", Map.of("type", "ONE_TIME", "runAt", runAt.toString()),
                "target", Map.of(
                        "url", "https://example.com/hooks/trial",
                        "method", "POST",
                        "payload", Map.of("userId", 42),
                        "timeoutMs", 5000));
    }
}
