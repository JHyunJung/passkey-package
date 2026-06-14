package com.crosscert.passkey.rpapp.web.relay

import com.crosscert.passkey.rpapp.config.RelayProperties
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 등록 relay 토큰 코덱(spec §5). {registrationToken, userHandle, username, displayName, exp} 를
 * HMAC-SHA256 으로 서명한 불투명 토큰 "base64url(payloadJson).base64url(hmac)" 을 만들고 검증한다.
 * 서명이 맞아야 payload 를 신뢰 → 클라이언트가 userHandle 을 조작할 수 없다. 무상태(자기완결).
 * username/displayName 을 함께 봉인해 finish 가 pending 없이도 결정적으로 user 를 확정할 수 있다(P0-4).
 */
@Component
class RegRelayCodec(props: RelayProperties, private val mapper: ObjectMapper) {

    /** 복원된 relay payload. */
    data class RegRelay(
        val registrationToken: String,
        val userHandle: String,
        val username: String,
        val displayName: String,
    )

    companion object {
        private const val HMAC_ALG = "HmacSHA256"
        private val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val B64D: Base64.Decoder = Base64.getUrlDecoder()
    }

    private val key: ByteArray = props.secret.toByteArray(StandardCharsets.UTF_8)
    private val ttlSeconds: Long = props.ttl.toSeconds()

    /** {rt, uh, un, dn, exp} 를 서명한 relay 토큰 생성. */
    fun encode(registrationToken: String, userHandle: String, username: String, displayName: String): String {
        val exp = Instant.now().epochSecond + ttlSeconds
        val p = ObjectNodePayload(registrationToken, userHandle, username, displayName, exp)
        val payload: ByteArray
        try {
            payload = mapper.writeValueAsBytes(p)
        } catch (e: Exception) {
            throw IllegalStateException("relay encode failed", e)
        }
        val p64 = B64.encodeToString(payload)
        val sig = B64.encodeToString(hmac(p64))
        return "$p64.$sig"
    }

    /** relay 토큰 검증·복원. 서명 불일치/만료/형식오류면 IllegalArgumentException. */
    fun decode(token: String?): RegRelay {
        if (token == null) throw IllegalArgumentException("relay token missing")
        val dot = token.indexOf('.')
        if (dot < 0) throw IllegalArgumentException("relay token malformed")
        val p64 = token.substring(0, dot)
        val sig = token.substring(dot + 1)
        val expected = hmac(p64)
        val actual: ByteArray
        try {
            actual = B64D.decode(sig)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("relay token bad signature encoding")
        }
        // 상수시간 비교(타이밍 공격 방지).
        if (!MessageDigest.isEqual(expected, actual)) {
            throw IllegalArgumentException("relay token bad signature")
        }
        val p: ObjectNodePayload
        try {
            p = mapper.readValue(B64D.decode(p64), ObjectNodePayload::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("relay token bad payload")
        }
        if (p.exp < Instant.now().epochSecond) {
            throw IllegalArgumentException("relay token expired")
        }
        // 필수 4필드 검증. 배포 직전 발급된 레거시 토큰(un/dn 없음)이 TTL 내에 finish 되면
        // un/dn 이 null 로 역직렬화되는데, 그대로 두면 upstream finish 후 confirmRegistration 의
        // byUsername.put(null,..) 에서 NPE(500) → credential 은 생성됐는데 매핑 누락. upstream
        // 호출 전 여기서 거부해 클라이언트가 등록을 깨끗이 재시작하게 한다(P0-4 무상태 계약).
        if (p.rt == null || p.uh == null || p.un == null || p.dn == null) {
            throw IllegalArgumentException("relay token incomplete payload")
        }
        return RegRelay(p.rt, p.uh, p.un, p.dn)
    }

    private fun hmac(data: String): ByteArray {
        try {
            val mac = Mac.getInstance(HMAC_ALG)
            mac.init(SecretKeySpec(key, HMAC_ALG))
            return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("relay hmac failed", e)
        }
    }

    /**
     * 직렬화 payload. 필드명을 짧게(rt/uh/un/dn/exp) 유지.
     * 원본 Java 는 record 라 plain ObjectMapper(파라미터명 메타데이터 없이도)로 역직렬화됐다.
     * Kotlin data class 는 그렇지 못하므로 @JsonCreator/@JsonProperty 로 생성자 바인딩을 명시해
     * jackson-module-kotlin 유무와 무관하게 record 와 동일하게 복원되도록 한다.
     */
    internal data class ObjectNodePayload @JsonCreator constructor(
        @JsonProperty("rt") val rt: String?,
        @JsonProperty("uh") val uh: String?,
        @JsonProperty("un") val un: String?,
        @JsonProperty("dn") val dn: String?,
        @JsonProperty("exp") val exp: Long,
    )
}
