package com.crosscert.passkey.sdk;

import org.slf4j.MDC;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * SDK 클라이언트 설정. 필수값(baseUrl, apiKeySupplier)은 {@link #builder} 인자로
 * 강제되고, 선택값은 미설정 시 기본값으로 치환된다.
 *
 * apiKeySupplier/traceIdProvider 의 반환값 null/blank 검증은 생성 시점이 아니라
 * 매 요청 시점(인터셉터)에서 한다 — 키가 도중에 비워질 수 있으므로.
 */
public final class PasskeyClientConfig {

    public static final String MDC_TRACE_ID_KEY = "traceId";

    private final URI baseUrl;
    private final Supplier<String> apiKeySupplier;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration jwksCacheTtl;
    private final Clock clock;
    private final Supplier<String> traceIdProvider;

    private PasskeyClientConfig(Builder b) {
        this.baseUrl = Objects.requireNonNull(b.baseUrl, "baseUrl");
        this.apiKeySupplier = Objects.requireNonNull(b.apiKeySupplier, "apiKeySupplier");
        this.connectTimeout = (b.connectTimeout != null) ? b.connectTimeout : Duration.ofSeconds(3);
        this.readTimeout = (b.readTimeout != null) ? b.readTimeout : Duration.ofSeconds(10);
        this.jwksCacheTtl = (b.jwksCacheTtl != null) ? b.jwksCacheTtl : Duration.ofMinutes(5);
        this.clock = (b.clock != null) ? b.clock : Clock.systemUTC();
        this.traceIdProvider = (b.traceIdProvider != null)
                ? b.traceIdProvider
                : () -> MDC.get(MDC_TRACE_ID_KEY);
    }

    public URI baseUrl() { return baseUrl; }
    public Supplier<String> apiKeySupplier() { return apiKeySupplier; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration readTimeout() { return readTimeout; }
    public Duration jwksCacheTtl() { return jwksCacheTtl; }
    public Clock clock() { return clock; }
    public Supplier<String> traceIdProvider() { return traceIdProvider; }

    public static Builder builder(URI baseUrl, Supplier<String> apiKeySupplier) {
        return new Builder(baseUrl, apiKeySupplier);
    }

    /** 모든 선택값에 기본값을 적용한 설정. (기존 외부 소비자/테스트 호환) */
    public static PasskeyClientConfig defaults(URI baseUrl, Supplier<String> apiKeySupplier) {
        return builder(baseUrl, apiKeySupplier).build();
    }

    public static final class Builder {
        private final URI baseUrl;
        private final Supplier<String> apiKeySupplier;
        private Duration connectTimeout;
        private Duration readTimeout;
        private Duration jwksCacheTtl;
        private Clock clock;
        private Supplier<String> traceIdProvider;

        private Builder(URI baseUrl, Supplier<String> apiKeySupplier) {
            this.baseUrl = baseUrl;
            this.apiKeySupplier = apiKeySupplier;
        }

        /** null 이면 기본값(3s) 적용. */
        public Builder connectTimeout(Duration v) { this.connectTimeout = v; return this; }
        /** null 이면 기본값(10s) 적용. */
        public Builder readTimeout(Duration v) { this.readTimeout = v; return this; }
        /** null 이면 기본값(5m) 적용. */
        public Builder jwksCacheTtl(Duration v) { this.jwksCacheTtl = v; return this; }
        /** null 이면 기본값(systemUTC) 적용. */
        public Builder clock(Clock v) { this.clock = v; return this; }
        /** null 이면 기본값(MDC.get("traceId")) 적용. */
        public Builder traceIdProvider(Supplier<String> v) { this.traceIdProvider = v; return this; }

        public PasskeyClientConfig build() { return new PasskeyClientConfig(this); }
    }
}
