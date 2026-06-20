package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyExpiryTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 6, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void activeWhenExpiresInFuture() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "pk_live", "hash", "k");
        key.expireAt(NOW.plusHours(1));
        assertThat(key.isActive(NOW)).isTrue();
    }

    @Test
    void inactiveWhenExpired() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "pk_live", "hash", "k");
        key.expireAt(NOW.minusSeconds(1));
        assertThat(key.isActive(NOW)).isFalse();
    }

    @Test
    void inactiveWhenRevoked() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "pk_live", "hash", "k");
        key.revoke(NOW);
        assertThat(key.isActive(NOW.plusHours(1))).isFalse();
    }
}
