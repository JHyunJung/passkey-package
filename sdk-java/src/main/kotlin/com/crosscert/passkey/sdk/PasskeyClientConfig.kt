package com.crosscert.passkey.sdk

import org.slf4j.MDC
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.Objects
import java.util.function.Supplier

/**
 * SDK 클라이언트 설정.
 *
 * 외부 Java 소비자 호환: 원본 Java `record` 의 정규 생성자가 선택값에 `null` 을
 * 받아 기본값으로 치환하던 동작을 그대로 보존한다. 즉
 * `new PasskeyClientConfig(base, supplier, null, null, null, null, null)` 처럼
 * 일부/전부 선택값을 null 로 넘기면 각각 기본값이 적용된다(원본 컴팩트 생성자와 동일).
 * 접근자는 record 식(`config.baseUrl()` 등) 으로 유지된다([JvmName]).
 *
 * apiKeySupplier 의 반환 키 null/blank 검증은 생성 시점이 아니라 매 요청 시점
 * (RedactingRequestInterceptor)에서 한다 — 키가 도중에 비워질 수 있으므로.
 */
class PasskeyClientConfig
@JvmOverloads
constructor(
    baseUrl: URI,
    apiKeySupplier: Supplier<String?>,
    connectTimeout: Duration? = null,
    readTimeout: Duration? = null,
    jwksCacheTtl: Duration? = null,
    clock: Clock? = null,
    traceIdProvider: Supplier<String?>? = null,
) {
    @get:JvmName("baseUrl")
    val baseUrl: URI = Objects.requireNonNull(baseUrl)

    @get:JvmName("apiKeySupplier")
    val apiKeySupplier: Supplier<String?> = Objects.requireNonNull(apiKeySupplier)

    @get:JvmName("connectTimeout")
    val connectTimeout: Duration = connectTimeout ?: Duration.ofSeconds(3)

    @get:JvmName("readTimeout")
    val readTimeout: Duration = readTimeout ?: Duration.ofSeconds(10)

    @get:JvmName("jwksCacheTtl")
    val jwksCacheTtl: Duration = jwksCacheTtl ?: Duration.ofMinutes(5)

    @get:JvmName("clock")
    val clock: Clock = clock ?: Clock.systemUTC()

    @get:JvmName("traceIdProvider")
    val traceIdProvider: Supplier<String?> = traceIdProvider ?: Supplier { MDC.get(MDC_TRACE_ID_KEY) }

    // 원본 Java record 의 값 동등성/해시/toString 을 보존(외부 소비자가 비교/로깅할 수 있음).
    // Supplier/Clock 은 의미 있는 value equality 가 없어 record 와 동일하게 인스턴스 동일성으로 비교된다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PasskeyClientConfig) return false
        return baseUrl == other.baseUrl &&
            apiKeySupplier == other.apiKeySupplier &&
            connectTimeout == other.connectTimeout &&
            readTimeout == other.readTimeout &&
            jwksCacheTtl == other.jwksCacheTtl &&
            clock == other.clock &&
            traceIdProvider == other.traceIdProvider
    }

    override fun hashCode(): Int =
        Objects.hash(baseUrl, apiKeySupplier, connectTimeout, readTimeout, jwksCacheTtl, clock, traceIdProvider)

    override fun toString(): String =
        "PasskeyClientConfig[baseUrl=$baseUrl, apiKeySupplier=$apiKeySupplier, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, jwksCacheTtl=$jwksCacheTtl, " +
            "clock=$clock, traceIdProvider=$traceIdProvider]"

    companion object {
        const val MDC_TRACE_ID_KEY: String = "traceId"

        @JvmStatic
        fun defaults(baseUrl: URI, apiKeySupplier: Supplier<String?>): PasskeyClientConfig =
            PasskeyClientConfig(baseUrl, apiKeySupplier)
    }
}
