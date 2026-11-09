package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.jwt.KeyEnvelope
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val PREFIX = "enc:v1:"

/**
 * admin_user.mfa_secret 의 at-rest 봉투 암호화 (P0 잔여 B).
 *
 * 저장 형식: `"enc:v1:" + base64(KeyEnvelope.seal(utf8(base32)))`.
 * [open]은 프리픽스가 없으면 평문으로 간주해 그대로 반환 — V37 이전에
 * 평문으로 저장된 기존 secret 을 무중단으로 읽기 위한 마이그레이션 경로다.
 * 평문→암호문 전환은 다음 enroll/confirm 시 seal 된 값을 저장하며 자연 발생한다.
 *
 * 실패는 KeyEnvelope 와 동일하게 generic IllegalStateException 으로
 * 표면화하여 secret/키 내용이 로그·예외에 새지 않도록 한다.
 */
@Component
class MfaSecretCipher(private val envelope: KeyEnvelope) {

    /** Base32 secret → sealed 저장 문자열. */
    fun seal(base32Secret: String?): String? {
        if (base32Secret == null) return null
        val sealed = envelope.seal(base32Secret.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(sealed)
    }

    /** 저장 문자열 → Base32 secret. 프리픽스 없으면 평문으로 간주해 그대로 반환. */
    fun open(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored // legacy 평문
        // NOTE: "enc:v2:" 추가 시 이 검사 BEFORE 에서 버전 분기할 것 —
        // v2 값은 PREFIX 와 불일치해 평문으로 오인된다.
        try {
            val sealed = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            return String(envelope.open(sealed), StandardCharsets.UTF_8)
        } catch (e: RuntimeException) {
            throw IllegalStateException("mfa secret open failed")
        }
    }
}
