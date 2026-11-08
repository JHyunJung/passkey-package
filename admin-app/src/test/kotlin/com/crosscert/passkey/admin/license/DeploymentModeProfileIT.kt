package com.crosscert.passkey.admin.license

import com.crosscert.passkey.core.license.FeatureGateAspect
import com.crosscert.passkey.core.license.LicenseBootstrap
import com.crosscert.passkey.core.license.LicenseGuardFilter
import com.crosscert.passkey.core.license.LicenseHeartbeatScheduler
import com.crosscert.passkey.core.license.LicenseProperties
import com.crosscert.passkey.core.license.LicensePublicKeyProvider
import com.crosscert.passkey.core.license.LicenseStateMachine
import com.crosscert.passkey.core.license.LicenseVerifier
import com.crosscert.passkey.core.license.OnpremTenantPinFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Clock

/**
 * Regression guard: ensure that the SaaS default (passkey.deployment.mode=saas)
 * does not register any of the onprem-only license beans. If a future
 * change drops the @ConditionalOnProperty guard, this test fails.
 *
 * Uses [ApplicationContextRunner] to load only the license-module
 * configuration and component classes. No DataSource, JPA, Redis, or Security
 * infrastructure is required — this is a pure conditional-wiring test.
 *
 * Verified beans (must NOT be present in SaaS mode):
 *  - [LicenseStateMachine] — produced by LicenseBootstrap (@ConditionalOnProperty)
 *  - [LicenseHeartbeatScheduler]
 *  - [LicenseGuardFilter]
 *  - [FeatureGateAspect]
 *  - [OnpremTenantPinFilter] — added in L2.5 plan-extension
 *
 * Bean that MUST always be present regardless of deployment mode:
 *  - [LicenseVerifier] — stateless, unconditional `@Component`
 */
class DeploymentModeProfileIT {

    /**
     * Minimal context: the license module's @Configuration class + all @Component
     * license beans + stub providers for their external dependencies.
     *
     * External dependencies are provided as mock stubs so no real infrastructure
     * (key files, config properties binding, clocks) is needed.
     */
    @Import(
        LicenseBootstrap::class,
        LicensePublicKeyProvider::class,
        LicenseVerifier::class,
        LicenseHeartbeatScheduler::class,
        LicenseGuardFilter::class,
        FeatureGateAspect::class,
        OnpremTenantPinFilter::class,
        DeploymentModeProfileIT.StubsConfig::class,
    )
    class LicenseModuleConfig

    /**
     * Provides stub beans required by LicenseVerifier and other license components.
     * These are never called in the SaaS-mode test — the conditional beans that
     * depend on them simply won't be registered.
     */
    class StubsConfig {
        @Bean
        fun licenseProperties(): LicenseProperties = mock()

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(LicenseModuleConfig::class.java)

    @Test
    fun saasMode_doesNotRegisterLicenseBeans() {
        runner.withPropertyValues("passkey.deployment.mode=saas")
            .run { ctx ->
                // Onprem-only beans must NOT be registered in SaaS mode.
                assertThat(ctx).doesNotHaveBean(LicenseStateMachine::class.java)
                assertThat(ctx).doesNotHaveBean(LicenseHeartbeatScheduler::class.java)
                assertThat(ctx).doesNotHaveBean(LicenseGuardFilter::class.java)
                assertThat(ctx).doesNotHaveBean(FeatureGateAspect::class.java)
                assertThat(ctx).doesNotHaveBean(OnpremTenantPinFilter::class.java)

                // Stateless verifier bean is unconditional — must always be present.
                assertThat(ctx).hasSingleBean(LicenseVerifier::class.java)
            }
    }
}
