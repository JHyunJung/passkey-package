package com.crosscert.passkey.core.mds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

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

    // ------------------------------------------------------------
    // G15-credN1 — multiLookup batches N Redis round-trips into one MGET.
    // ------------------------------------------------------------

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private MdsAaguidCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new MdsAaguidCache(redis);
    }

    @Test
    void multiLookup_issuesSingleMgetForMultipleAaguids() {
        UUID u1 = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID u2 = UUID.fromString("22222222-2222-3333-4444-555555555555");
        byte[] a1 = uuidBytes(u1);
        byte[] a2 = uuidBytes(u2);

        when(valueOps.multiGet(List.of("mds:aaguid:" + u1, "mds:aaguid:" + u2)))
                .thenReturn(List.of("FIDO_CERTIFIED_L1", "FIDO_CERTIFIED_L1,REVOKED"));

        Map<UUID, MdsAaguidCache.Entry> result = cache.multiLookup(List.of(a1, a2));

        assertThat(result).hasSize(2);
        assertThat(result.get(u1).statuses()).containsExactly("FIDO_CERTIFIED_L1");
        assertThat(result.get(u2).statuses()).containsExactly("FIDO_CERTIFIED_L1", "REVOKED");
        // The whole point of G15: exactly one round-trip regardless of N aaguids.
        verify(valueOps, times(1)).multiGet(anyList());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void multiLookup_omitsMissingAndBlankEntriesFromResultMap() {
        UUID present = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID missing = UUID.fromString("22222222-2222-3333-4444-555555555555");
        UUID blank = UUID.fromString("33333333-2222-3333-4444-555555555555");

        when(valueOps.multiGet(anyList()))
                .thenReturn(java.util.Arrays.asList("FIDO_CERTIFIED_L1", null, ""));

        Map<UUID, MdsAaguidCache.Entry> result =
                cache.multiLookup(List.of(uuidBytes(present), uuidBytes(missing), uuidBytes(blank)));

        assertThat(result).containsOnlyKeys(present);
    }

    @Test
    void multiLookup_emptyInput_returnsEmptyMapWithoutCallingRedis() {
        Map<UUID, MdsAaguidCache.Entry> result = cache.multiLookup(List.of());

        assertThat(result).isEmpty();
        verify(valueOps, never()).multiGet(anyList());
    }

    private static byte[] uuidBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
