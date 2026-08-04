package dev.pranay.chronos.api;

import dev.pranay.chronos.security.ApiKeyService;
import dev.pranay.chronos.security.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Tenant provisioning.
 *
 * <p><b>Deliberately unauthenticated, and deliberately not production-ready.</b> There is a
 * bootstrap problem — you cannot present an API key before any key exists — and this resolves it
 * the simplest way that keeps the rest of the system honest.
 *
 * <p>In a real deployment this would sit behind an admin credential, an internal-only network, or
 * an out-of-band provisioning flow. Leaving it open would let anyone mint themselves a tenant. That
 * is written here rather than left implicit, because an unauthenticated create-tenant endpoint is
 * exactly the kind of thing that survives into production by being unremarkable.
 */
@RestController
@RequestMapping("/v1/tenants")
public class TenantController {

    private final ApiKeyService apiKeyService;

    public TenantController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * Creates a tenant and returns its credentials.
     *
     * <p>This response is the <em>only</em> time the API key is readable. Only its hash is stored,
     * so there is no "show my key" endpoint and cannot be one — which is the property that makes a
     * database dump useless to an attacker.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyService.IssuedKey create(@Valid @RequestBody CreateTenantRequest request) {
        return apiKeyService.createTenant(request.name());
    }

    /**
     * Rotates the signing secret, keeping the previous one valid.
     *
     * <p>Both secrets verify during the window. Rotation that invalidates the old secret instantly
     * breaks every consumer the moment you press it, which in practice means nobody ever rotates —
     * so the overlap is what makes the feature real rather than decorative.
     */
    @PostMapping("/{id}/rotate-secret")
    public RotatedSecret rotateSecret(@PathVariable String id) {
        if (!TenantContext.requireId().equals(id)) {
            throw new JobNotFoundException(id);
        }
        return new RotatedSecret(apiKeyService.rotateSigningSecret(id));
    }

    public record CreateTenantRequest(@NotBlank @Size(max = 100) String name) {}

    public record RotatedSecret(String signingSecret) {}
}
