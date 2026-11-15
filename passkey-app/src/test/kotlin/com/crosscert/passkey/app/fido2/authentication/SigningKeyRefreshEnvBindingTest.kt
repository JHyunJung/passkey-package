package com.crosscert.passkey.app.fido2.authentication

import com.crosscert.passkey.core.jwt.SigningKeyProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * 문서·compose 에 적은 환경변수 이름이 [SigningKeyRefreshJob] 의 `@Scheduled` placeholder 에 실제로 들어가는지 확인한다.
 * Boot 가 쓰는 것과 같이 systemEnvironment 소스를 [SystemEnvironmentPropertySource] 로 두고
 * [ConfigurationPropertySources.attach] 한 환경에서 어노테이션 문자열 그대로를 resolve 한다.
 * 빈 fixed-delay 는 기본값이 적용되지 않아 일회성 작업이 되므로 잡 빈 생성에서 기동이 막히는지도 확인한다.
 */
class SigningKeyRefreshEnvBindingTest {

    private val scheduled: Scheduled =
        SigningKeyRefreshJob::class.java.getMethod("runOnce").getAnnotation(Scheduled::class.java)

    private fun envWith(vars: Map<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars),
            )
            ConfigurationPropertySources.attach(this)
        }

    @Test
    fun documentedEnvVarNames_bindToScheduleDelays() {
        val env = envWith(
            mapOf(
                "PASSKEY_SIGNING_KEY_REFRESH_FIXED_DELAY" to "PT2M",
                "PASSKEY_SIGNING_KEY_REFRESH_INITIAL_DELAY" to "PT3M",
            ),
        )

        assertThat(env.resolvePlaceholders(scheduled.fixedDelayString)).isEqualTo("PT2M")
        assertThat(env.resolvePlaceholders(scheduled.initialDelayString)).isEqualTo("PT3M")
    }

    @Test
    fun unsetEnvVars_fallBackToPT1M() {
        val env = envWith(emptyMap())

        assertThat(env.resolvePlaceholders(scheduled.fixedDelayString)).isEqualTo("PT1M")
        assertThat(env.resolvePlaceholders(scheduled.initialDelayString)).isEqualTo("PT1M")
    }

    @Test
    fun blankFixedDelay_failsStartup() {
        val env = envWith(mapOf("PASSKEY_SIGNING_KEY_REFRESH_FIXED_DELAY" to ""))
        assertThat(env.resolvePlaceholders(scheduled.fixedDelayString)).isEmpty()

        assertThatThrownBy { startContext(env).close() }
            .rootCause()
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("passkey.signing-key.refresh.fixed-delay")
            .hasMessageContaining("PASSKEY_SIGNING_KEY_REFRESH_FIXED_DELAY")
    }

    @Test
    fun unsetOrBlankInitialDelay_startsWithJobBean() {
        for (vars in listOf(emptyMap(), mapOf<String, Any>("PASSKEY_SIGNING_KEY_REFRESH_INITIAL_DELAY" to ""))) {
            startContext(envWith(vars)).use { ctx ->
                assertThat(ctx.getBean(SigningKeyRefreshJob::class.java)).isNotNull
            }
        }
    }

    private fun startContext(env: StandardEnvironment): AnnotationConfigApplicationContext =
        AnnotationConfigApplicationContext().apply {
            environment = env
            register(SchedulingConfig::class.java)
            refresh()
        }

    @Configuration
    @EnableScheduling
    @Import(SigningKeyRefreshJob::class)
    class SchedulingConfig {
        @Bean fun signingKeyProvider(): SigningKeyProvider = mock()
    }
}
