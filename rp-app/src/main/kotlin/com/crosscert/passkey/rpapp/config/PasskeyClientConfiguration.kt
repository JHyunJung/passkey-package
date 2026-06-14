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
        // SDK 의 PasskeyClientConfig 생성자는 선택값에 null 을 받으면 기본값으로 치환한다
        // (원본 Java record 컴팩트 생성자와 동일). 따라서 nullable 프로퍼티를 그대로 넘긴다.
        // baseUrl 은 필수 — 원본 Java 의 Objects.requireNonNull 과 동일하게 누락 시 fail-fast(!!).
        PasskeyClient.of(
            PasskeyClientConfig(
                props.baseUrl!!,
                apiKeySupplier,
                props.connectTimeout,
                props.readTimeout,
                props.jwksCacheTtl,
                null, // clock: SDK 기본 Clock.systemUTC()
                null, // traceIdProvider: SDK 기본 MDC.get("traceId")
            ),
        )
}
