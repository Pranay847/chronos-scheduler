package dev.pranay.chronos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Creates indexes at startup (§2.5).
 *
 * <p>Spring Data stopped auto-creating indexes from {@code @Indexed} annotations by default in
 * 3.0, and that default is the right one — implicit index creation on a large production
 * collection is a foreground build that can stall the application. Declaring them here instead
 * keeps every index visible in one file, in the same order as the design document, and makes
 * {@code idx_claim}'s field ordering an explicit decision rather than a side effect of where
 * someone put an annotation.
 *
 * <p>Only {@code jobs} is indexed in Phase 1 because it is the only collection that exists yet.
 * {@code job_executions} (with its TTL), {@code dead_letters}, and {@code tenants} join this
 * class as their phases land.
 */
@Component
public class MongoIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        var jobs = mongoTemplate.indexOps("jobs");

        // THE claim index. Every poll cycle on every worker hits this.
        //
        // Field order is the entire point: `status` is an equality match and must come first,
        // `nextRunAt` is a range scan and must come second. Reversed, Mongo can still use the
        // index but has to examine every future-dated document of every status to find the
        // pending ones — the plan's own §12 lists the resulting COLLSCAN as a sharp edge.
        //
        // Phase 8 benchmarks this against a partial variant:
        //   .partial(PartialIndexFilter.of(Criteria.where("status").is("PENDING")))
        // which indexes only claimable rows. Most documents in a mature collection are terminal,
        // so the size delta should be large. Measure it rather than assuming.
        jobs.createIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("nextRunAt", Sort.Direction.ASC)
                .named("idx_claim"));

        // Reaper sweep — find expired leases without scanning live ones.
        jobs.createIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("lockExpiresAt", Sort.Direction.ASC)
                .named("idx_reaper"));

        // Tenant-scoped listing. `status` sits in the middle because GET /v1/jobs filters on
        // tenant AND status while sorting by createdAt; without it the status filter happens
        // in memory across the tenant's entire history.
        jobs.createIndex(new Index()
                .on("tenantId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named("idx_tenant_listing"));

        var executions = mongoTemplate.indexOps("job_executions");

        // Attempt history for one job — GET /v1/jobs/{id}/executions.
        executions.createIndex(new Index()
                .on("jobId", Sort.Direction.ASC)
                .on("attempt", Sort.Direction.DESC)
                .named("idx_execution_history"));

        // Debugging "did this firing go out more than once?", and the assertion the chaos test
        // in Phase 8 leans on.
        executions.createIndex(new Index()
                .on("idempotencyKey", Sort.Direction.ASC)
                .named("idx_idempotency_key"));

        // TTL: a retention policy for free, with no cron job to own and forget about.
        //
        // Two things make TTL indexes fail silently. The field must be a BSON date — a string
        // timestamp indexes fine and expires nothing — and the background sweeper only runs about
        // once a minute, so a test that writes a record and immediately expects it gone will fail
        // for reasons that have nothing to do with the index.
        executions.createIndex(new Index()
                .on("createdAt", Sort.Direction.ASC)
                .expire(Duration.ofDays(30))
                .named("idx_execution_ttl"));

        var deadLetters = mongoTemplate.indexOps("dead_letters");

        // The listing query: GET /v1/dead-letters is tenant-scoped and newest-first.
        deadLetters.createIndex(new Index()
                .on("tenantId", Sort.Direction.ASC)
                .on("failedAt", Sort.Direction.DESC)
                .named("idx_deadletter_listing"));

        // "Did this job ever dead-letter, and why?" — the question you ask while debugging one
        // specific job, which is when you least want a collection scan.
        deadLetters.createIndex(new Index()
                .on("jobId", Sort.Direction.ASC)
                .named("idx_deadletter_job"));

        log.info("Ensured indexes: jobs={}, job_executions={}, dead_letters={}",
                jobs.getIndexInfo().size(), executions.getIndexInfo().size(), deadLetters.getIndexInfo().size());
    }
}
