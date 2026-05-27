package com.crosscert.passkey.samplerp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "passkey")
public record PasskeyProperties(
        URI baseUrl,
        String apiKey,
        String tenantId,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl
) {}
