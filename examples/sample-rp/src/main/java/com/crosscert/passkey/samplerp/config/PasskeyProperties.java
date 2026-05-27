package com.crosscert.passkey.samplerp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "passkey")
public record PasskeyProperties(
        URI baseUrl,
        String apiKey,
        String tenantId,
        /**
         * ID Token 의 `iss` claim 비교용 prefix. passkey-app 의 `IdTokenIssuer` 가
         * `passkey.id-token.issuer-base:https://passkey.crosscert.com` + "/" + tenantId 로 발급한다.
         * 로컬 데모에서는 passkey-app 의 `application-local.yml` 등에서 issuer-base 를
         * baseUrl 과 동일하게 override 하거나, 본 프로퍼티를 그 값과 맞춰야 한다.
         */
        URI issuerBase,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl
) {}
