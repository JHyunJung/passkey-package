package com.crosscert.passkey.app.fido2.authentication

import com.crosscert.passkey.core.jwt.SigningKeyProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SigningKeyRefreshJob::class.java)

/**
 * ID Token 서명 키 캐시를 DB 의 ACTIVE 키에 맞춘다. 서명 키 회전은 admin-app 에서 일어나고 거기서 부르는
 * [SigningKeyProvider.reload] 는 admin-app 프로세스의 캐시만 바꾸므로, 이 프로세스는 스스로 주기적으로
 * [SigningKeyProvider.refreshIfRotated] 를 불러야 한다. kid 가 같으면 DB 조회 한 번으로 끝난다.
 *
 * 인스턴스마다 자기 캐시를 갱신해야 하므로 분산 lease 없이 모든 인스턴스가 실행한다.
 * 회전 뒤 갱신 전까지는 ROTATED 가 된 옛 키로 서명하고, 그 토큰은 ID Token 수명(`passkey.id-token.ttl`, 기본 PT15M)
 * 동안 검증돼야 하므로 갱신 주기 + ID Token 수명이 회전 grace(admin-app `passkey.key-rotation.grace`, 기본 PT30M)
 * 이하여야 한다(기본값에서 주기 ≤ PT15M).
 *
 * fixed-delay 가 빈 문자열이면(.env 의 `VAR=`) 기본값이 적용되지 않고 @Scheduled 가 initial-delay 뒤 한 번만
 * 실행하는 작업으로 등록되므로, 생성자에서 기동을 막는다.
 */
@Component
class SigningKeyRefreshJob(
    private val keys: SigningKeyProvider,
    @Value("\${passkey.signing-key.refresh.fixed-delay:PT1M}") fixedDelay: String,
) {

    init {
        require(fixedDelay.isNotBlank()) {
            "passkey.signing-key.refresh.fixed-delay (env PASSKEY_SIGNING_KEY_REFRESH_FIXED_DELAY) must not be blank — " +
                "remove the variable to use the default PT1M"
        }
    }

    @Scheduled(
        fixedDelayString = "\${passkey.signing-key.refresh.fixed-delay:PT1M}",
        initialDelayString = "\${passkey.signing-key.refresh.initial-delay:PT1M}",
    )
    fun runOnce() {
        // 예외를 밖으로 내보내지 않는다 — 실패하면 기존 키로 계속 서명하고 다음 tick 에 다시 확인한다.
        try {
            if (keys.refreshIfRotated()) {
                log.info("signing key refreshed: kid={}", keys.signingKey().keyID)
            }
        } catch (e: RuntimeException) {
            log.warn("signing key refresh failed — keeping cached key", e)
        }
    }
}
