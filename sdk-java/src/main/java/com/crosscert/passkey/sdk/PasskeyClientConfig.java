package com.crosscert.passkey.sdk;

import org.slf4j.MDC;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public record PasskeyClientConfig(
        URI baseUrl,
        Supplier<String> apiKeySupplier,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl,
        Clock clock,
        Supplier<String> traceIdProvider
) {
    public static final String MDC_TRACE_ID_KEY = "traceId";

    public PasskeyClientConfig {
        Objects.requireNonNull(baseUrl);
        // Supplier 인스턴스는 non-null. 반환되는 키 문자열의 null/blank 검증은
        // 매 요청 시점(RedactingRequestInterceptor)에서 — 키가 도중에 비워질 수
        // 있으므로 생성 시점 1회 검증으로는 부족하다.
        Objects.requireNonNull(apiKeySupplier);
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
        if (readTimeout == null)    readTimeout    = Duration.ofSeconds(10);
        if (jwksCacheTtl == null)   jwksCacheTtl   = Duration.ofMinutes(5);
        if (clock == null)          clock          = Clock.systemUTC();
        if (traceIdProvider == null) traceIdProvider = () -> MDC.get(MDC_TRACE_ID_KEY);
    }

    public static PasskeyClientConfig defaults(URI baseUrl, Supplier<String> apiKeySupplier) {
        return new PasskeyClientConfig(baseUrl, apiKeySupplier, null, null, null, null, null);
    }
}
