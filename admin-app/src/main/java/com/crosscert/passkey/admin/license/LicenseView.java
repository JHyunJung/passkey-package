package com.crosscert.passkey.admin.license;

import java.time.Instant;
import java.util.List;

/**
 * Serialized view of license state for admin UI consumption.
 * deploymentMode included so the SPA can decide whether to show
 * the License menu at all.
 */
public record LicenseView(
        String deploymentMode,
        String state,
        String sub,
        String jti,
        Instant expiresAt,
        long daysUntilExpiry,
        List<String> features,
        Instant lastVerifiedAt,
        Long graceRemainingHours,
        Instant nextHeartbeatAt
) {}
