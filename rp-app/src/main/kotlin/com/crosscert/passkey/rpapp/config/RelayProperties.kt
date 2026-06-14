package com.crosscert.passkey.rpapp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 등록 relay 토큰 HMAC 서명 설정(spec §5). registrationToken↔userHandle 바인딩용.
 * secret 은 운영 시 환경변수로 주입(데모 기본값은 비밀 아님). ttl 은 passkey-app
 * challenge TTL(5분)과 정렬.
 *
 * 생성자 파라미터(nullable)를 받아 정규화한 non-null val 을 노출한다. 본 클래스는 @Bean factory
 * 로 제공되지 않고 @ConfigurationPropertiesScan 으로만 등록되므로 Spring 이 VALUE_OBJECT(생성자)
 * 바인딩을 쓴다 — 따라서 setter 없는 derived val 로도 정상 바인딩된다(WellKnown/Cors 와 달리
 * factory @Bean 강제 JAVA_BEAN 경로를 타지 않는다). RelayKeyGuardTest 는 `RelayProperties(null,..)`
 * 로 DEMO_SECRET 폴백을 검증하므로 정규화는 노출 프로퍼티에 반영된다.
 */
@ConfigurationProperties(prefix = "rp.relay")
class RelayProperties(secret: String?, ttl: Duration?) {

    // 데모 폴백. non-dev 프로필에선 RelayKeyGuard 가 차단(P2-a).
    val secret: String = if (secret.isNullOrBlank()) RelayKeyGuard.DEMO_SECRET else secret
    val ttl: Duration = ttl ?: Duration.ofMinutes(5)
}
