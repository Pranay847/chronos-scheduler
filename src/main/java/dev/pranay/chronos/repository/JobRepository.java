package dev.pranay.chronos.repository;

import dev.pranay.chronos.domain.Job;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Job persistence.
 *
 * <p>The atomic claim and the lease-verified writes live in {@link JobRepositoryCustom}, because
 * none of them can be expressed as a derived query — they need {@code findAndModify} with a sort,
 * and conditional updates that re-assert lease ownership.
 *
 * <p>Reads are tenant-scoped by construction. Taking {@code tenantId} as a parameter rather than
 * filtering after the fact means a cross-tenant read has to be written deliberately instead of
 * happening by omission.
 */
public interface JobRepository extends MongoRepository<Job, String>, JobRepositoryCustom {

    Optional<Job> findByIdAndTenantId(String id, String tenantId);
}
