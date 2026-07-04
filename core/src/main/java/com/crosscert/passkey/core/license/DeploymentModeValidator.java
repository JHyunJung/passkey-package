package com.crosscert.passkey.core.license;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * G12-deployMode fail-fast guard, wired unconditionally (no
 * {@code @ConditionalOnProperty}) so it runs regardless of what value
 * {@code passkey.deployment.mode} actually holds — see {@link DeploymentModeGuard}
 * for why an unrecognized value is dangerous rather than merely cosmetic.
 *
 * <p>No default value on the {@code @Value} injection: a completely missing
 * property is just as unsafe as a typo'd one, so it must fail the same way.
 */
@Component
class DeploymentModeValidator {

    private final String rawMode;

    DeploymentModeValidator(@Value("${passkey.deployment.mode}") String rawMode) {
        this.rawMode = rawMode;
    }

    @PostConstruct
    void validate() {
        DeploymentModeGuard.assertValid(rawMode);
    }
}
