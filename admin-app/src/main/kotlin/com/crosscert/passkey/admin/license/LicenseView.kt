package com.crosscert.passkey.admin.license

import java.time.Instant

/**
 * Serialized view of license state for admin UI consumption.
 * deploymentMode included so the SPA can decide whether to show
 * the License menu at all.
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
