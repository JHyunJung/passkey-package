package com.crosscert.passkey.admin.license

import java.time.Instant

/**
 * Serialized view of license state for admin UI consumption.
 * deploymentMode included so callers can tell onprem from SaaS
 * (LicensePageController 는 onprem 이 아니면 안내만, ShellController 는 onprem 이고 VALID 가 아닐 때만 라이선스 배너).
 */
data class LicenseView(
    val deploymentMode: String?,
    val state: String?,
    val sub: String?,
    val jti: String?,
    val expiresAt: Instant?,
    val daysUntilExpiry: Long,
    val features: List<String>?,
    val lastVerifiedAt: Instant?,
    val graceRemainingHours: Long?,
    val nextHeartbeatAt: Instant?,
)
