package com.crosscert.passkey.admin.web.format;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/** API 키 만료 임박 판정(← admin-ui lib/apiKeyExpiry.ts). */
public final class ApiKeyExpiry {

    public static final int EXPIRY_WARN_DAYS = 30;

    private ApiKeyExpiry() {}

    /** 회수되지 않았고, 아직 유효하며, now 로부터 days 일 이내에 만료되는 키. 무기한(expiresAt 없음)은 false. */
    public static boolean isExpiringSoon(OffsetDateTime revokedAt, OffsetDateTime expiresAt, Instant now, int days) {
        if (revokedAt != null || expiresAt == null) return false;
        Instant exp = expiresAt.toInstant();
        return exp.isAfter(now) && Duration.between(now, exp).compareTo(Duration.ofDays(days)) <= 0;
    }

    public static boolean isExpiringSoon(OffsetDateTime revokedAt, OffsetDateTime expiresAt, Instant now) {
        return isExpiringSoon(revokedAt, expiresAt, now, EXPIRY_WARN_DAYS);
    }
}
