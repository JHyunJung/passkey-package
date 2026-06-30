package com.crosscert.passkey.core.mds;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MdsAaguidCacheTest {

    @Test
    void canonicalAaguid_16bytes_ok() {
        byte[] a = new byte[16];
        a[0] = 0x01;
        UUID u = MdsAaguidCache.canonicalAaguid(a);
        assertEquals("01000000-0000-0000-0000-000000000000", u.toString());
    }

    @Test
    void canonicalAaguid_shortArray_throwsIllegalArgument() {
        byte[] tooShort = new byte[8];
        assertThrows(IllegalArgumentException.class,
                () -> MdsAaguidCache.canonicalAaguid(tooShort));
    }

    @Test
    void canonicalAaguid_null_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> MdsAaguidCache.canonicalAaguid(null));
    }
}
