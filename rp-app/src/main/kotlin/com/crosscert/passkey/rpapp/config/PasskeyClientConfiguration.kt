package com.crosscert.passkey.rpapp.config

import com.crosscert.passkey.sdk.PasskeyClient
import com.crosscert.passkey.sdk.PasskeyClientConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.function.Supplier

@Configuration
class PasskeyClientConfiguration {

    /**
     * 재기동 없이 API Key 를 교체/회수 반영하기 위한 동적 키 소스.
     * api-key-file 이 설정되면 그 파일을 핫리로드하고, 아니면 api-key(env)를 폴백한다.
     */
    @Bean
    fun apiKeySupplier(props: PasskeyProperties): Supplier<String?> {
        val reload = props.apiKeyReload ?: Duration.ofSeconds(10)
        return ReloadableApiKeySupplier(props.apiKeyFile, reload, props.apiKey)
    }

    @Bean
    fun passkeyClient(props: PasskeyProperties, apiKeySupplier: Supplier<String?>): PasskeyClient =
        PasskeyClient.of(
            PasskeyClientConfig(
                props.baseUrl,
                apiKeySupplier,
                props.connectTimeout,
                props.readTimeout,
                props.jwksCacheTtl,
                Clock.systemUTC(),
                null, // SDK 가 MDC.get("traceId") 기본값 사용
            ),
        )
}
