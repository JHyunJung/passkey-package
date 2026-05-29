package com.crosscert.passkey.admin.license;

import com.crosscert.passkey.core.license.FeatureGateAspect;
import com.crosscert.passkey.core.license.LicenseBootstrap;
import com.crosscert.passkey.core.license.LicenseGuardFilter;
import com.crosscert.passkey.core.license.LicenseHeartbeatScheduler;
import com.crosscert.passkey.core.license.LicenseProperties;
import com.crosscert.passkey.core.license.LicensePublicKeyProvider;
import com.crosscert.passkey.core.license.LicenseStateMachine;
import com.crosscert.passkey.core.license.LicenseVerifier;
import com.crosscert.passkey.core.license.OnpremTenantPinFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression guard: ensure that the SaaS default (passkey.deployment.mode=saas)
 * does not register any of the onprem-only license beans. If a future
 * change drops the @ConditionalOnProperty guard, this test fails.
 *
 * <p>Uses {@link ApplicationContextRunner} to load only the license-module
 * configuration and component classes. No DataSource, JPA, Redis, or Security
 * infrastructure is required — this is a pure conditional-wiring test.
 *
 * <p>Verified beans (must NOT be present in SaaS mode):
 * <ul>
 *   <li>{@link LicenseStateMachine} — produced by LicenseBootstrap (@ConditionalOnProperty)</li>
 *   <li>{@link LicenseHeartbeatScheduler}</li>
 *   <li>{@link LicenseGuardFilter}</li>
 *   <li>{@link FeatureGateAspect}</li>
 *   <li>{@link OnpremTenantPinFilter} — added in L2.5 plan-extension</li>
 * </ul>
 *
 * <p>Bean that MUST always be present regardless of deployment mode:
 * <ul>
 *   <li>{@link LicenseVerifier} — stateless, unconditional {@code @Component}</li>
 * </ul>
 */
class DeploymentModeProfileIT {

    /**
     * Minimal context: the license module's @Configuration class + all @Component
     * license beans + stub providers for their external dependencies.
     *
     * <p>External dependencies are provided as mock stubs so no real infrastructure
     * (key files, config properties binding, clocks) is needed.
     */
    @Import({
            LicenseBootstrap.class,
            LicensePublicKeyProvider.class,
            LicenseVerifier.class,
            LicenseHeartbeatScheduler.class,
            LicenseGuardFilter.class,
            FeatureGateAspect.class,
            OnpremTenantPinFilter.class,
            DeploymentModeProfileIT.StubsConfig.class
    })
    static class LicenseModuleConfig {
    }

    /**
     * Provides stub beans required by LicenseVerifier and other license components.
     * These are never called in the SaaS-mode test — the conditional beans that
     * depend on them simply won't be registered.
     */
    static class StubsConfig {
        @Bean
        LicenseProperties licenseProperties() {
            return mock(LicenseProperties.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LicenseModuleConfig.class);

    @Test
    void saasMode_doesNotRegisterLicenseBeans() {
        runner.withPropertyValues("passkey.deployment.mode=saas")
                .run(ctx -> {
                    // Onprem-only beans must NOT be registered in SaaS mode.
                    assertThat(ctx).doesNotHaveBean(LicenseStateMachine.class);
                    assertThat(ctx).doesNotHaveBean(LicenseHeartbeatScheduler.class);
                    assertThat(ctx).doesNotHaveBean(LicenseGuardFilter.class);
                    assertThat(ctx).doesNotHaveBean(FeatureGateAspect.class);
                    assertThat(ctx).doesNotHaveBean(OnpremTenantPinFilter.class);

                    // Stateless verifier bean is unconditional — must always be present.
                    assertThat(ctx).hasSingleBean(LicenseVerifier.class);
                });
    }
}
