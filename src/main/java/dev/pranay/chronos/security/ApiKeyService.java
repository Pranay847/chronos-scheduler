package dev.pranay.chronos.security;

import dev.pranay.chronos.domain.Tenant;
import dev.pranay.chronos.repository.TenantRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues and resolves API keys.
 *
 * <h2>Why SHA-256 here and not bcrypt/argon2</h2>
 *
 * <p>Password hashing is deliberately slow to make guessing a human-chosen secret expensive. An
 * API key is not human-chosen: it is 256 bits from a CSPRNG, so there is nothing to guess and
 * nothing a rainbow table can precompute. What matters instead is that resolving a key happens on
 * <em>every request</em> — a deliberately-slow hash there would put ~100ms of work in front of
 * every API call to defend against an attack that does not apply.
 *
 * <p>Being able to explain that distinction is worth more than reflexively reaching for bcrypt.
 * The rule it comes from: slow hashes protect low-entropy secrets; high-entropy secrets need
 * constant-time comparison and storage as a digest, which is what this does.
 */
@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "chr_";

    private final TenantRepository tenantRepository;

    public ApiKeyService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Creates a tenant and returns its plaintext key <em>once</em>.
     *
     * <p>Only the hash is persisted, so this return value is the only time the key exists in a
     * readable form. That is the point: a database dump must not yield working credentials.
     */
    public IssuedKey createTenant(String name) {
        String apiKey = KEY_PREFIX + randomToken(32);
        String signingSecret = "whsec_" + randomToken(32);

        Tenant tenant = Tenant.create(name, hash(apiKey), signingSecret);
        Tenant saved = tenantRepository.save(tenant);

        return new IssuedKey(saved.getId(), apiKey, signingSecret);
    }

    /** Rotates the signing secret, keeping the previous one valid during the window. */
    public String rotateSigningSecret(String tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No tenant " + tenantId));
        String next = "whsec_" + randomToken(32);
        tenant.rotateSecret(next);
        tenantRepository.save(tenant);
        return next;
    }

    /**
     * The stand-in tenant used when authentication is switched off.
     *
     * <p>Created on demand with a fixed id so that jobs written in single-tenant mode are still
     * owned by a real tenant document — which is what keeps the signing path working and means
     * turning authentication on later does not orphan existing data.
     */
    public Tenant getOrCreateDefaultTenant(String tenantId) {
        return tenantRepository.findById(tenantId).orElseGet(() -> {
            Tenant tenant = Tenant.create("default (auth disabled)",
                    hash("local-development-key"), "whsec_" + randomToken(32));
            return tenantRepository.save(withId(tenant, tenantId));
        });
    }

    private static Tenant withId(Tenant tenant, String id) {
        try {
            var field = Tenant.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(tenant, id);
            return tenant;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to assign fixed tenant id", e);
        }
    }

    public Optional<Tenant> resolve(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        return tenantRepository.findByApiKeyHash(hash(presentedKey));
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /**
     * A newly created tenant's credentials.
     *
     * @param apiKey        shown once and never again
     * @param signingSecret what the tenant verifies webhook signatures with
     */
    public record IssuedKey(String tenantId, String apiKey, String signingSecret) {}
}
