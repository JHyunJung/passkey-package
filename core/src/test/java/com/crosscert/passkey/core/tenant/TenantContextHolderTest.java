package com.crosscert.passkey.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextHolderTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void getReturnsNullWhenNotSet() {
        assertNull(TenantContextHolder.get());
    }

    @Test
    void setAndGet() {
        TenantContextHolder.set(TENANT_A);
        assertEquals(TENANT_A, TenantContextHolder.get());
    }

    @Test
    void clearRemovesValue() {
        TenantContextHolder.set(TENANT_A);
        TenantContextHolder.clear();
        assertNull(TenantContextHolder.get());
    }

    @Test
    void setNullClearsContext() {
        TenantContextHolder.set(TENANT_A);
        TenantContextHolder.set(null);
        assertNull(TenantContextHolder.get());
    }

    @Test
    void valueIsThreadLocal() throws Exception {
        TenantContextHolder.set(TENANT_A);
        AtomicReference<UUID> otherThreadValue = new AtomicReference<>();
        Thread t = new Thread(() -> {
            otherThreadValue.set(TenantContextHolder.get());
            TenantContextHolder.set(TENANT_B);
        });
        t.start();
        t.join();
        assertNull(otherThreadValue.get(), "other thread should not see main thread's tenant");
        assertEquals(TENANT_A, TenantContextHolder.get());
    }
}
