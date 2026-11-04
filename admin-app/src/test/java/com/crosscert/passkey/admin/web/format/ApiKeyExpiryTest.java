package com.crosscert.passkey.admin.web.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** ← admin-ui/src/lib/apiKeyExpiry.test.ts (isExpiringSoon 30일) */
class ApiKeyExpiryTest {

    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso);
    }

    @Test
    @DisplayName("30일 이내 만료 예정 ACTIVE 키는 true")
    void expiringWithin30DaysIsTrue() {
        assertThat(ApiKeyExpiry.isExpiringSoon(null, at("2026-10-21T00:00:00Z"), NOW)).isTrue();
        assertThat(ApiKeyExpiry.isExpiringSoon(null, at("2026-11-10T00:00:00Z"), NOW)).isTrue(); // 정확히 30일
    }

    @Test
    @DisplayName("30일 초과는 false")
    void moreThan30DaysIsFalse() {
        assertThat(ApiKeyExpiry.isExpiringSoon(null, at("2026-11-10T00:00:01Z"), NOW)).isFalse();
    }

    @Test
    @DisplayName("이미 만료·회수·무기한은 false")
    void expiredRevokedOrUnlimitedIsFalse() {
        assertThat(ApiKeyExpiry.isExpiringSoon(null, at("2026-10-10T00:00:00Z"), NOW)).isFalse();
        assertThat(ApiKeyExpiry.isExpiringSoon(at("2026-10-01T00:00:00Z"), at("2026-10-21T00:00:00Z"), NOW)).isFalse();
        assertThat(ApiKeyExpiry.isExpiringSoon(null, null, NOW)).isFalse(); // {}
        assertThat(ApiKeyExpiry.isExpiringSoon(null, null, NOW)).isFalse(); // { expiresAt: null }
    }
}
