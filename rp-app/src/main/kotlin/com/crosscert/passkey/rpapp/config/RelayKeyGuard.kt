package com.crosscert.passkey.rpapp.config

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** non-dev/local 프로필에서 relay 데모 키 사용을 차단(P2-a, spec §5). */
@Component
class RelayKeyGuard(
    private val props: RelayProperties,
    private val env: Environment,
) {

    companion object {
        /** 데모/개발용 기본 HMAC 키. [RelayProperties] 폴백과 공유(상수 한곳 정의). */
        const val DEMO_SECRET = "dev-rp-relay-secret-not-for-prod-change-me"
    }

    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        val active = env.activeProfiles.toSet()
        // dev/local 이 명시적으로 active 일 때만 데모 키 허용. 빈 프로필(env-only 운영,
        // spring.profiles.active 미설정)은 운영으로 간주해 데모 키를 거부한다.
        val devOrLocal = active.contains("dev") || active.contains("local")
        if (!devOrLocal && DEMO_SECRET == props.secret) {
            throw IllegalStateException(
                "rp.relay.secret 이 데모 기본 키입니다. 운영(또는 프로필 미지정) 환경에서는 RP_RELAY_SECRET 로 강한 키를 주입하세요.",
            )
        }
    }
}
