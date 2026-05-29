package com.crosscert.passkey.core.license;

import java.time.Instant;
import java.util.Set;

/**
 * Verified license token — represents trusted claims AFTER signature
 * and aud/iss/nbf checks pass. Constructed by LicenseVerifier only.
 */
public record LicenseToken(
        String sub,
        String jti,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        String tenantId,
        Set<String> features,
        LicenseLimits limits
) {
    public boolean hasFeature(String name) {
        return features.contains(name);
    }
}
