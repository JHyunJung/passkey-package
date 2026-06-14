package com.crosscert.passkey.rpapp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 등록 relay 토큰 HMAC 서명 설정(spec §5). registrationToken↔userHandle 바인딩용.
 * secret 은 운영 시 환경변수로 주입(데모 기본값은 비밀 아님). ttl 은 passkey-app
 * challenge TTL(5분)과 정렬.
 */
@ConfigurationProperties(prefix = "rp.relay")
class RelayProperties(secret: String?, ttl: Duration?) {

    // 데모 폴백. non-dev 프로필에선 RelayKeyGuard 가 차단(P2-a).
    val secret: String = if (secret.isNullOrBlank()) RelayKeyGuard.DEMO_SECRET else secret
    val ttl: Duration = ttl ?: Duration.ofMinutes(5)
}
