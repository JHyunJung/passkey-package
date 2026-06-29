package com.crosscert.passkey.rpapp.config

import com.crosscert.passkey.sdk.PasskeyClient
import com.crosscert.passkey.sdk.PasskeyClientConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
        // SDK 의 Builder 는 필수값(baseUrl, apiKeySupplier)을 인자로 강제하고, 선택값은
        // null 을 넘기면 기본값으로 치환한다. baseUrl 은 필수 — 누락 시 fail-fast(!!).
        PasskeyClient.of(
            PasskeyClientConfig.builder(props.baseUrl!!, apiKeySupplier)
                .connectTimeout(props.connectTimeout)
                .readTimeout(props.readTimeout)
                .jwksCacheTtl(props.jwksCacheTtl)
                .build(),
        )
}
