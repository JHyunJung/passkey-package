package com.crosscert.passkey.core.mds;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed AAGUID → MdsEntry cache. Keys follow the
 * {@code mds:aaguid:<UUID>} pattern so MdsSchedulerService can
 * invalidate the whole set on a new BLOB by DELing the matching key range.
 *
 * <p>Phase 3 scope: status report list only. Future phases may expand
 * the cached value to include the full webauthn4j MetadataStatement.
 */
@Component
public class MdsAaguidCache {

    private final StringRedisTemplate redis;

    public MdsAaguidCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Entry> lookup(byte[] aaguid) {
        UUID uuid = canonicalAaguid(aaguid);
        String key = "mds:aaguid:" + uuid;
        String csv = redis.opsForValue().get(key);
        if (csv == null || csv.isBlank()) return Optional.empty();
        return Optional.of(new Entry(List.of(csv.split(","))));
    }

    /**
     * G15: batch lookup for N distinct AAGUIDs in a single Redis round-trip
     * (MGET), instead of the caller issuing one {@link #lookup} GET per row.
     * Used by CredentialAdminService.toView when rendering a page of up to
     * 200 credentials — one round-trip regardless of page size.
     *
     * <p>Order-preserving: {@code aaguids} and the MGET result list share
     * the same index, mirroring Redis MGET semantics (missing key → null
     * at that position). The returned map omits any AAGUID whose cached
     * value is absent or blank — callers should treat a missing map entry
     * the same way {@link #lookup} treats {@link Optional#empty()}.
     */
    public Map<UUID, Entry> multiLookup(List<byte[]> aaguids) {
        if (aaguids == null || aaguids.isEmpty()) return Map.of();

        List<UUID> uuids = new ArrayList<>(aaguids.size());
        List<String> keys = new ArrayList<>(aaguids.size());
        for (byte[] aaguid : aaguids) {
            UUID uuid = canonicalAaguid(aaguid);
            uuids.add(uuid);
            keys.add("mds:aaguid:" + uuid);
        }

        List<String> values = redis.opsForValue().multiGet(keys);
        Map<UUID, Entry> result = new LinkedHashMap<>();
        if (values == null) return result;
        for (int i = 0; i < uuids.size() && i < values.size(); i++) {
            String csv = values.get(i);
            if (csv == null || csv.isBlank()) continue;
            result.put(uuids.get(i), new Entry(List.of(csv.split(","))));
        }
        return result;
    }

    public void put(byte[] aaguid, Entry entry, Duration ttl) {
        UUID uuid = canonicalAaguid(aaguid);
        String key = "mds:aaguid:" + uuid;
        String csv = String.join(",", entry.statuses);
        redis.opsForValue().set(key, csv, ttl);
    }

    /** AAGUID = 16-byte raw → UUID canonical form. */
    public static UUID canonicalAaguid(byte[] aaguid) {
        if (aaguid == null || aaguid.length != 16) {
            throw new IllegalArgumentException("aaguid must be 16 bytes");
        }
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (aaguid[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (aaguid[i] & 0xff);
        return new UUID(msb, lsb);
    }

    public record Entry(List<String> statuses) {
        public Entry { statuses = List.copyOf(statuses); }
    }
}
