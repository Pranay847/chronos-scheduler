package dev.pranay.chronos.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An API consumer.
 *
 * <p>The collection the original three-collection design had nowhere to put: §6.1 needs a
 * per-tenant signing secret and §6.4 needs a hashed API key, and neither belongs on a job.
 *
 * <p><b>Only the hash of the API key is stored.</b> A database dump, a stray log line, or a
 * backup left somewhere it shouldn't be must not hand anyone a working credential. The plaintext
 * key exists exactly once, in the response to the call that created the tenant, and is
 * unrecoverable after that — which is the correct trade and worth saying out loud in the docs so
 * nobody expects a "show my key" endpoint.
 */
@Document(collection = "tenants")
public class Tenant {

    @Id
    private String id;

    private String name;

    /** SHA-256 of the bearer token. Never the token itself. */
    @Indexed(unique = true)
    private String apiKeyHash;

    /**
     * Active signing secrets, newest first.
     *
     * <p>Two at most. Rotation is only useful if both the old and new secret verify for a window —
     * otherwise every consumer breaks the instant you rotate, which means nobody ever rotates. New
     * signatures always use the primary; the second exists so receivers still validating against
     * the previous one keep working until they catch up.
     */
    private List<SigningSecret> signingSecrets = new ArrayList<>();

    /** Job creations allowed per minute. */
    private int jobsPerMinute = 1_000;

    private Instant createdAt;

    protected Tenant() {
        // for Spring Data
    }

    public static Tenant create(String name, String apiKeyHash, String initialSecret) {
        Tenant tenant = new Tenant();
        tenant.name = name;
        tenant.apiKeyHash = apiKeyHash;
        tenant.signingSecrets = new ArrayList<>(List.of(new SigningSecret(initialSecret, Instant.now())));
        tenant.createdAt = Instant.now();
        return tenant;
    }

    /** The secret new signatures are computed with. */
    public String primarySecret() {
        if (signingSecrets.isEmpty()) {
            throw new IllegalStateException("Tenant " + id + " has no signing secret");
        }
        return signingSecrets.getFirst().secret();
    }

    /**
     * Adds a new primary secret, retiring the oldest if there would be more than two.
     *
     * <p>Capped deliberately. An uncapped list grows forever and quietly widens the window in which
     * an old, possibly leaked secret still verifies.
     */
    public void rotateSecret(String newSecret) {
        signingSecrets.addFirst(new SigningSecret(newSecret, Instant.now()));
        while (signingSecrets.size() > 2) {
            signingSecrets.removeLast();
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public List<SigningSecret> getSigningSecrets() {
        return List.copyOf(signingSecrets);
    }

    public int getJobsPerMinute() {
        return jobsPerMinute;
    }

    public void setJobsPerMinute(int jobsPerMinute) {
        this.jobsPerMinute = jobsPerMinute;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public record SigningSecret(String secret, Instant createdAt) {}
}
