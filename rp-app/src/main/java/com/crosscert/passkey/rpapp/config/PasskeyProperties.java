package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * passkey-app 연동 설정({@code passkey.*}). 고객사는 발급받은 base-url / api-key / tenant-id /
 * issuer-base 를 환경변수나 yml 로 주입한다.
 */
@ConfigurationProperties(prefix = "passkey")
public record PasskeyProperties(
        URI baseUrl,
        String apiKey,
        /**
         * 설정 시 이 파일에서 API Key 를 핫리로드한다(env 보다 우선). 미설정이면
         * apiKey 를 폴백으로 쓴다(기존 동작 보존). 파일 내용은 키 평문 한 줄.
         */
        Path apiKeyFile,
        /** apiKeyFile mtime 폴링 주기. 기본 10s. */
        Duration apiKeyReload,
        String tenantId,
        /**
         * ID Token 의 {@code iss} claim 비교용 prefix. passkey-app 이 {@code <issuer-base>/<tenantId>}
         * 형태로 iss 를 발급하므로, 이 값은 passkey-app 의 issuer-base 설정과 정확히 일치해야 한다.
         * 운영에서는 자사 테넌트에 발급된 issuer-base 를 주입한다.
         */
        URI issuerBase,
        /** passkey-app 연결 타임아웃. 기본 3s. */
        Duration connectTimeout,
        /** passkey-app 응답 읽기 타임아웃. 기본 10s. */
        Duration readTimeout,
        /** ID Token 검증용 JWKS 캐시 유효 기간. 기본 5m. */
        Duration jwksCacheTtl
) {}
