package com.crosscert.passkey.core.vpd;

import java.util.UUID;

/**
 * Thread-bound tenant context. The tenant id is a UUID since Phase 6;
 * the JDBC layer in {@link TenantAwareDataSource} converts to a
 * 32-char hex string when calling CTX_PKG.SET_TENANT.
 *
 * <p>Phase 0 invariant preserved: when no tenant is set, the VPD
 * policy returns '1=0' (no rows visible).
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
