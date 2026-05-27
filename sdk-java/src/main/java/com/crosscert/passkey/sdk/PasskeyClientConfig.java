package com.crosscert.passkey.sdk;

import org.slf4j.MDC;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public record PasskeyClientConfig(
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl,
        Clock clock,
        Supplier<String> traceIdProvider
) {
    public static final String MDC_TRACE_ID_KEY = "traceId";

    public PasskeyClientConfig {
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(apiKey);
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
        if (readTimeout == null)    readTimeout    = Duration.ofSeconds(10);
        if (jwksCacheTtl == null)   jwksCacheTtl   = Duration.ofMinutes(5);
        if (clock == null)          clock          = Clock.systemUTC();
        if (traceIdProvider == null) traceIdProvider = () -> MDC.get(MDC_TRACE_ID_KEY);
    }

    public static PasskeyClientConfig defaults(URI baseUrl, String apiKey) {
        return new PasskeyClientConfig(baseUrl, apiKey, null, null, null, null, null);
    }
}
