package dev.pranay.chronos.repository;

import dev.pranay.chronos.domain.DeadLetter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DeadLetterRepository extends MongoRepository<DeadLetter, String> {

    Page<DeadLetter> findByTenantIdOrderByFailedAtDesc(String tenantId, Pageable pageable);

    Optional<DeadLetter> findByIdAndTenantId(String id, String tenantId);

    List<DeadLetter> findByJobId(String jobId);
}
