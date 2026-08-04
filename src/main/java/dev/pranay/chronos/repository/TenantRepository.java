package dev.pranay.chronos.repository;

import dev.pranay.chronos.domain.Tenant;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface TenantRepository extends MongoRepository<Tenant, String> {

    /**
     * Resolves a tenant from the hash of a presented key.
     *
     * <p>Looked up by hash rather than by scanning and comparing, so the lookup is an indexed
     * equality match and the plaintext key never has to exist in the database at all.
     */
    Optional<Tenant> findByApiKeyHash(String apiKeyHash);
}
