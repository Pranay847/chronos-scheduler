package dev.pranay.chronos.security;

import dev.pranay.chronos.domain.Tenant;

/**
 * The tenant behind the request currently being handled.
 *
 * <p>A {@link ThreadLocal} rather than a parameter threaded through every signature, because it is
 * ambient to the whole request. The important part is {@link #clear()} running in a {@code finally}
 * in the filter: on a pooled request thread, a value left behind is inherited by whoever runs next,
 * which is a cross-tenant data leak produced by a missing line of cleanup.
 *
 * <p>Virtual threads make that less likely — one thread per request, discarded after — but the
 * dispatcher pool is virtual while Tomcat's request threads are not, so the discipline stays.
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    /**
     * The authenticated tenant.
     *
     * @throws IllegalStateException if called outside an authenticated request — a bug, not a
     *                               condition to handle, since the filter rejects anonymous calls
     *                               before any handler runs
     */
    public static Tenant require() {
        Tenant tenant = CURRENT.get();
        if (tenant == null) {
            throw new IllegalStateException("No tenant bound to this thread — is the API key filter mapped?");
        }
        return tenant;
    }

    public static String requireId() {
        return require().getId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
