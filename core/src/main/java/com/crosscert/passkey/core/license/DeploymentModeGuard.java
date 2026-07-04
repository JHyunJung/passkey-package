package com.crosscert.passkey.core.license;

import java.util.Locale;
import java.util.Set;

/**
 * G12-deployMode fail-fast guard.
 *
 * <p>{@code passkey.deployment.mode} gates every onprem license component
 * ({@link LicenseGuardFilter}, {@link LicenseBootstrap}, {@link OnpremTenantPinFilter},
 * {@link LicenseHeartbeatScheduler}, {@link FeatureGateAspect}, {@link LicenseHealthIndicator})
 * via {@code @ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")}.
 * Every one of those annotations independently compares the raw property string —
 * there is no shared enum, and {@code @ConditionalOnProperty} has no
 * {@code matchIfMissing}/allow-list concept. A single-character typo in the
 * operator-supplied value (e.g. {@code onprem } with a trailing space, or
 * {@code on-prem}) silently fails every one of those conditions to "no match":
 * the Spring context boots successfully with zero license enforcement — no
 * heartbeat, no tenant pin, no feature gate, no DEAD-state 503 — for an onprem
 * deployment that believes it is licensed.
 *
 * <p>Fail fast instead: this component is unconditional (no
 * {@code @ConditionalOnProperty}) and rejects any value that is not exactly
 * {@code saas} or {@code onprem} (case-insensitive, trimmed). An operator typo
 * now aborts context startup with a clear message instead of shipping a
 * silently-unlicensed onprem server.
 */
final class DeploymentModeGuard {
    private DeploymentModeGuard() {}

    static final String SAAS = "saas";
    static final String ONPREM = "onprem";
    private static final Set<String> ALLOWED = Set.of(SAAS, ONPREM);

    /**
     * @throws IllegalStateException if {@code rawMode} (trimmed, case-insensitive)
     *         is not one of {@code saas} / {@code onprem}.
     */
    static void assertValid(String rawMode) {
        String normalized = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(normalized)) {
            throw new IllegalStateException(
                    "passkey.deployment.mode must be exactly 'saas' or 'onprem' "
                            + "(case-insensitive) — got '" + rawMode + "'. An unrecognized value "
                            + "silently disables ALL onprem license enforcement "
                            + "(LicenseGuardFilter/LicenseBootstrap/OnpremTenantPinFilter/"
                            + "LicenseHeartbeatScheduler/FeatureGateAspect are gated on the exact "
                            + "string 'onprem' and simply do not register). Refusing to boot.");
        }
    }
}
