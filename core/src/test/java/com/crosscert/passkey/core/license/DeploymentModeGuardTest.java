package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G12-deployMode: passkey.deployment.mode must be exactly "saas" or "onprem"
 * (case-insensitive, trimmed). Any other value — most importantly an operator
 * typo like "on-prem" or "onprem " — must fail fast rather than silently
 * disabling every onprem license component (they are all gated on
 * havingValue = "onprem", with no matchIfMissing / allow-list semantics).
 */
class DeploymentModeGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"saas", "onprem", "SAAS", "OnPrem", " saas ", " onprem"})
    void acceptsKnownModes(String mode) {
        assertThatCode(() -> DeploymentModeGuard.assertValid(mode)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"on-prem", "onpremm", "on prem", "SAS", "prod", "enterprise"})
    void rejectsTypoedModes(String mode) {
        assertThatThrownBy(() -> DeploymentModeGuard.assertValid(mode))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("passkey.deployment.mode");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> DeploymentModeGuard.assertValid(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> DeploymentModeGuard.assertValid(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
