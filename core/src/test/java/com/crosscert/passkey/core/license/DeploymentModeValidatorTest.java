package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G12-deployMode: {@link DeploymentModeValidator} must run unconditionally
 * (no @ConditionalOnProperty gate) and fail context startup when
 * passkey.deployment.mode is an unrecognized value — this is the actual
 * boot-time behavior the unit-level {@link DeploymentModeGuardTest} backs.
 */
class DeploymentModeValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DeploymentModeValidator.class);

    @Test
    void bootSucceeds_whenModeIsSaas() {
        runner.withPropertyValues("passkey.deployment.mode=saas")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void bootSucceeds_whenModeIsOnprem() {
        runner.withPropertyValues("passkey.deployment.mode=onprem")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void bootFails_whenModeIsTypoed() {
        runner.withPropertyValues("passkey.deployment.mode=on-prem")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx).getFailure().hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void bootFails_whenModeIsMissing() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }
}
