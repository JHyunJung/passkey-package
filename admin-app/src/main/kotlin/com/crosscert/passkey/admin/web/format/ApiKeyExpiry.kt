package com.crosscert.passkey.admin.web.format

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** API 키 만료 임박 판정(← admin-ui lib/apiKeyExpiry.ts). */
object ApiKeyExpiry {

    const val EXPIRY_WARN_DAYS = 30

    /** 회수되지 않았고, 아직 유효하며, now 로부터 days 일 이내에 만료되는 키. 무기한(expiresAt 없음)은 false. */
    fun isExpiringSoon(revokedAt: OffsetDateTime?, expiresAt: OffsetDateTime?, now: Instant, days: Int): Boolean {
        if (revokedAt != null || expiresAt == null) return false
        val exp = expiresAt.toInstant()
        return exp.isAfter(now) && Duration.between(now, exp) <= Duration.ofDays(days.toLong())
    }

    fun isExpiringSoon(revokedAt: OffsetDateTime?, expiresAt: OffsetDateTime?, now: Instant): Boolean =
        isExpiringSoon(revokedAt, expiresAt, now, EXPIRY_WARN_DAYS)
}
