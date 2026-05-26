package com.crosscert.passkey.app.fido2.mds;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
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

    public void put(byte[] aaguid, Entry entry, Duration ttl) {
        UUID uuid = canonicalAaguid(aaguid);
        String key = "mds:aaguid:" + uuid;
        String csv = String.join(",", entry.statuses);
        redis.opsForValue().set(key, csv, ttl);
    }

    /** AAGUID = 16-byte raw → UUID canonical form. */
    static UUID canonicalAaguid(byte[] aaguid) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (aaguid[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (aaguid[i] & 0xff);
        return new UUID(msb, lsb);
    }

    public record Entry(List<String> statuses) {
        public Entry { statuses = List.copyOf(statuses); }
    }
}
