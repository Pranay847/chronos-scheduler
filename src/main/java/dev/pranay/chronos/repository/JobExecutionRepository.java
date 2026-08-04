package dev.pranay.chronos.repository;

import dev.pranay.chronos.domain.JobExecution;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Query surface for the execution audit trail.
 *
 * <p>Reads only, by convention. Execution records are <em>written</em> through the dedicated
 * {@code executionMongoTemplate} (see {@code MongoConfig}) so they get {@code w:1} instead of the
 * majority concern job state uses — they are the highest-volume write in the system and losing
 * their tail in a failover is survivable. A repository {@code save()} here would silently go out
 * at majority and quietly become the slowest thing in the delivery path.
 */
public interface JobExecutionRepository extends MongoRepository<JobExecution, String> {

    List<JobExecution> findByJobIdOrderByAttemptDesc(String jobId);

    List<JobExecution> findByIdempotencyKey(String idempotencyKey);

    long countByJobId(String jobId);
}
