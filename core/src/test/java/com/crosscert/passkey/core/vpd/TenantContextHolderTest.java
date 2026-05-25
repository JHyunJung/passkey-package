package com.crosscert.passkey.core.vpd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextHolderTest {

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
        TenantContextHolder.set("T_A");
        assertEquals("T_A", TenantContextHolder.get());
    }

    @Test
    void clearRemovesValue() {
        TenantContextHolder.set("T_A");
        TenantContextHolder.clear();
        assertNull(TenantContextHolder.get());
    }

    @Test
    void valueIsThreadLocal() throws Exception {
        TenantContextHolder.set("T_MAIN");
        final String[] otherThreadValue = new String[1];
        Thread t = new Thread(() -> otherThreadValue[0] = TenantContextHolder.get());
        t.start();
        t.join();
        assertNull(otherThreadValue[0], "other thread should not see main thread's tenant");
        assertEquals("T_MAIN", TenantContextHolder.get());
    }
}
