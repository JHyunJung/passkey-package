package com.crosscert.passkey.core.tenant;

import java.util.UUID;

/**
 * Thread-bound tenant context. The tenant id is a UUID, consumed by
 * {@link TenantFilterAspect} to enable the Hibernate {@code tenantFilter}
 * for app-level tenant isolation.
 *
 * <p>Invariant: when no tenant is set, the filter is not enabled (the
 * cross-tenant / PLATFORM_OPERATOR case).
 */
public final class TenantContextHolder {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    public static void set(UUID tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(tenantId);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    private TenantContextHolder() {}
}
