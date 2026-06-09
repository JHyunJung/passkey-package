package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 등록 relay 토큰 HMAC 서명 설정(spec §5). registrationToken↔userHandle 바인딩용.
 * secret 은 운영 시 환경변수로 주입(데모 기본값은 비밀 아님). ttl 은 passkey-app
 * challenge TTL(5분)과 정렬.
 */
@ConfigurationProperties(prefix = "rp.relay")
public record RelayProperties(String secret, Duration ttl) {
    public RelayProperties {
        if (secret == null || secret.isBlank()) {
            secret = "dev-rp-relay-secret-not-for-prod-change-me";
        }
        if (ttl == null) ttl = Duration.ofMinutes(5);
    }
}
