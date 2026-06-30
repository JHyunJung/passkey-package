package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 등록 릴레이 토큰의 HMAC 서명 설정({@code rp.relay.*}). registrationToken↔userHandle 바인딩에 쓴다.
 * <b>secret 은 운영에서 반드시 강한 키로 주입</b>한다(미설정이면 데모 기본 키가 쓰이고, RelayKeyGuard 가
 * 운영 프로필에서 기동을 차단한다). ttl 은 passkey-app 의 challenge 만료(기본 5분)와 맞춘다.
 */
@ConfigurationProperties(prefix = "rp.relay")
public class RelayProperties {

    private final String secret;
    private final Duration ttl;

    public RelayProperties(String secret, Duration ttl) {
        // 미설정/빈 값이면 데모 기본 키로 폴백한다. 운영 프로필에서는 RelayKeyGuard 가 이 기본 키를 거부한다.
        this.secret = (secret == null || secret.isBlank()) ? RelayKeyGuard.DEMO_SECRET : secret;
        this.ttl = (ttl == null) ? Duration.ofMinutes(5) : ttl;
    }

    public String secret() { return secret; }
    public Duration ttl() { return ttl; }
}
