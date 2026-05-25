package com.crosscert.passkey.core.vpd;

public final class TenantContextHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
