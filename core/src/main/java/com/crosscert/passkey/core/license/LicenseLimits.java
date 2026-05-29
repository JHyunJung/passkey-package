package com.crosscert.passkey.core.license;

/**
 * Per-license tunables embedded in the JWS payload.
 * Defaults applied when fields are absent from the token.
 */
public record LicenseLimits(
        int warningDaysBeforeExpiry,
        int graceHoursWhenOffline
) {
    public static final LicenseLimits DEFAULTS = new LicenseLimits(30, 72);
}
