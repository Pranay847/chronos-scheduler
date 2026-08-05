package dev.pranay.chronos.security;

import dev.pranay.chronos.domain.Tenant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import dev.pranay.chronos.config.ChronosProperties;
import dev.pranay.chronos.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves the calling tenant from {@code Authorization: Bearer <key>}.
 *
 * <p>Replaces the fixed tenant id the earlier phases used. That placeholder was deliberately a
 * constant rather than a request header: an unauthenticated {@code X-Tenant-Id} would have let any
 * caller name any tenant, which looks like isolation while providing none — a worse starting point
 * than being honestly single-tenant.
 *
 * <p>Everything downstream already takes a tenant id, so this changes where the value comes from
 * and nothing else.
 */
@Component
@Order(1)
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String BEARER = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final SecurityProperties securityProperties;
    private final ChronosProperties chronosProperties;

    public ApiKeyFilter(ApiKeyService apiKeyService, SecurityProperties securityProperties,
                        ChronosProperties chronosProperties) {
        this.apiKeyService = apiKeyService;
        this.securityProperties = securityProperties;
        this.chronosProperties = chronosProperties;
        if (!securityProperties.requireApiKey()) {
            log.warn("API KEY AUTHENTICATION DISABLED (chronos.security.require-api-key=false). "
                    + "Every request is treated as tenant '{}'.", chronosProperties.defaultTenantId());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Health and metrics are infrastructure endpoints — a liveness probe has no API key, and
        // Prometheus scrapes are secured at the network layer rather than with a tenant credential.
        // Tenant creation is bootstrap: there is no key to present before the first one exists.
        //
        // The landing page is exempt because it is a static file with nothing tenant-scoped on it.
        // Without this the root URL answers a browser with a raw 401 problem document, which is
        // correct behaviour and a terrible front door. Note this is an exact-match list, not a
        // prefix: `/` must not become a prefix rule or it exempts the entire API.
        return path.startsWith("/actuator")
                || path.equals("/v1/tenants")
                || isLandingPage(path);
    }

    private static boolean isLandingPage(String path) {
        return path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Single-tenant mode: no credential is presented and everything belongs to one tenant.
        // Deliberately not a silent fallback when a key IS presented but invalid — that would let
        // a bad key quietly succeed, which is worse than either behaviour on its own.
        Optional<Tenant> tenant = securityProperties.requireApiKey()
                ? extractKey(request).flatMap(apiKeyService::resolve)
                : Optional.of(apiKeyService.getOrCreateDefaultTenant(chronosProperties.defaultTenantId()));

        if (tenant.isEmpty()) {
            // Same response for "no key", "malformed key" and "key that doesn't exist". Telling
            // them apart would let someone probe which keys are real.
            unauthorized(response);
            return;
        }

        try {
            TenantContext.set(tenant.get());
            chain.doFilter(request, response);
        } finally {
            // Non-negotiable. Request threads are pooled, so a value left behind here is inherited
            // by the next request on this thread — a cross-tenant leak caused by a missing finally.
            TenantContext.clear();
        }
    }

    private Optional<String> extractKey(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        String key = header.substring(BEARER.length()).trim();
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://chronos.dev/problems/unauthorized",\
                "title":"Unauthorized",\
                "status":401,\
                "detail":"Provide a valid API key as: Authorization: Bearer <key>"}""");
    }
}
