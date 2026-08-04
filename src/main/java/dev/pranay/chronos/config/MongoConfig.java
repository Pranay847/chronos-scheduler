package dev.pranay.chronos.config;

import com.mongodb.WriteConcern;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;

/**
 * Durability settings for job state.
 *
 * <h2>Why the write concern is set explicitly</h2>
 *
 * <p>The driver default is {@code w:1} — acknowledged by the primary alone, before the write has
 * reached any secondary. On a single-node replica set that distinction is meaningless, which is
 * exactly why it survives local development unnoticed. On a real replica set it is a correctness
 * hole in the one mechanism this whole service is built around: a claim can be acknowledged, the
 * primary can step down before that write replicates, and the newly elected primary still shows
 * the job {@code PENDING}. A second worker claims it and the job runs twice — with no bug in any
 * code path, and nothing in the logs to suggest one.
 *
 * <p>{@code w:majority} closes that. It costs latency on every claim, which is the correct trade
 * for a scheduler whose entire pitch is not double-firing.
 *
 * <p><b>Phase 2 note:</b> when {@code job_executions} arrives, it does <em>not</em> need majority.
 * It is an audit trail, losing its tail on a failover is survivable, and it is the highest-volume
 * write in the system. Give that collection its own template with {@code w:1} rather than paying
 * majority latency on every delivery record.
 */
@Configuration
public class MongoConfig {

    /**
     * Majority as the <em>client-wide floor</em>.
     *
     * <p>Setting it here rather than only on the template matters for two reasons.
     *
     * <p>First, defence: anything that reaches Mongo without going through {@link MongoTemplate} —
     * a raw {@code MongoClient} injection, a driver-level utility, a future maintenance script —
     * inherits majority instead of silently getting the driver's {@code w:1} default. The floor
     * holds regardless of which door the write comes through.
     *
     * <p>Second, observability. Template-level write concern is applied <em>per operation</em>,
     * inside {@code doInsert}/{@code doUpdate}, via {@code collection.withWriteConcern(...)}.
     * Spring Data's {@code prepareCollection} only ever applies {@code readPreference}, so a
     * collection handle obtained from the template reports {@code w=null} no matter what the
     * template is configured with. That makes template-level configuration effectively
     * unassertable without reflection. Set at client level it propagates
     * client → database → collection and can simply be read back, which is what
     * {@code JobApiIntegrationTest#jobWritesUseMajorityWriteConcern} does.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer writeConcernCustomizer() {
        return builder -> builder.writeConcern(WriteConcern.MAJORITY);
    }

    /**
     * Job-state template, majority-acknowledged.
     *
     * <p>Redundant with the client floor above by design: this is the line that states the policy
     * for job state specifically, and it is the line Phase 2 copies and changes when
     * {@code job_executions} gets its own template. Those are the highest-volume writes in the
     * system and they are an audit trail — losing their tail in a failover is survivable, so
     * paying majority latency on every delivery record is not. A per-operation concern set on
     * that template overrides the client default, so the split works without weakening this one.
     */
    @Bean
    @Primary
    public MongoTemplate mongoTemplate(MongoDatabaseFactory databaseFactory, MongoConverter converter) {
        MongoTemplate template = new MongoTemplate(databaseFactory, converter);
        template.setWriteConcern(WriteConcern.MAJORITY);
        return template;
    }

    /**
     * Execution-record template, acknowledged by the primary only.
     *
     * <p>This is the split the comment above anticipates. {@code job_executions} is written once
     * per delivery attempt — the highest-volume write in the system, on the latency-sensitive
     * path, and by nature an audit trail rather than state anything depends on. Losing its tail in
     * a failover costs you some rows in a dashboard; paying majority latency on every delivery
     * costs you throughput on the metric the project is judged by.
     *
     * <p>Job state keeps majority. The asymmetry is the point: durability is bought where a lost
     * write changes behaviour, and not where it doesn't.
     */
    @Bean
    public MongoTemplate executionMongoTemplate(MongoDatabaseFactory databaseFactory, MongoConverter converter) {
        MongoTemplate template = new MongoTemplate(databaseFactory, converter);
        template.setWriteConcern(WriteConcern.W1);
        return template;
    }
}
