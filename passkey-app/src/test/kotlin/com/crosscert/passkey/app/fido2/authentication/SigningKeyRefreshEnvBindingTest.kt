package com.crosscert.passkey.app.fido2.authentication

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.scheduling.annotation.Scheduled

/**
 * 문서·compose 에 적은 환경변수 이름이 [SigningKeyRefreshJob] 의 `@Scheduled` placeholder 에 실제로 들어가는지 확인한다.
 * Boot 가 쓰는 것과 같이 systemEnvironment 소스를 [SystemEnvironmentPropertySource] 로 두고
 * [ConfigurationPropertySources.attach] 한 환경에서 어노테이션 문자열 그대로를 resolve 한다.
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
}
