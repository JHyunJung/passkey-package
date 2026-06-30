package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 등록 relay 토큰 HMAC 서명 설정(spec §5). registrationToken↔userHandle 바인딩용.
 * secret 은 운영 시 환경변수로 주입(데모 기본값은 비밀 아님). ttl 은 passkey-app
 * challenge TTL(5분)과 정렬.
 *
 * 생성자 파라미터(nullable)를 받아 정규화한 non-null 값을 노출한다. @ConfigurationPropertiesScan
 * 으로만 등록되므로 Spring 이 VALUE_OBJECT(생성자) 바인딩을 쓴다 — derived 필드라도 정상 바인딩.
 * RelayKeyGuardTest 는 new RelayProperties(null, ..) 로 DEMO_SECRET 폴백을 검증한다.
 */
@ConfigurationProperties(prefix = "rp.relay")
public class RelayProperties {

    private final String secret;
    private final Duration ttl;

    public RelayProperties(String secret, Duration ttl) {
        // 데모 폴백. non-dev 프로필에선 RelayKeyGuard 가 차단(P2-a).
        this.secret = (secret == null || secret.isBlank()) ? RelayKeyGuard.DEMO_SECRET : secret;
        this.ttl = (ttl == null) ? Duration.ofMinutes(5) : ttl;
    }

    public String secret() { return secret; }
    public Duration ttl() { return ttl; }
}
