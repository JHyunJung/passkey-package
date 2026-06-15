package com.crosscert.passkey.rpapp.web

import java.util.regex.Pattern

/**
 * 로그 메시지에서 비밀값(API key/JWT/password/bcrypt)을 마스킹하는 공유 헬퍼.
 * 텍스트 출력의 [SecretMaskingConverter] 와 JSON 출력의 RedactingMessageJsonProvider 가
 * 같은 로직을 공유해 drift 를 막는다.
 *
 * Mirror of `com.crosscert.passkey.core.logging.SecretRedactor` — rp-app does not
 * depend on :core, so the class is duplicated here. Keep the two in sync.
 */
object SecretRedactor {

    // X-API-Key: pk_… header form (apply BEFORE standalone pk_ pattern).
    // Capture both the header name AND the pk_ + 8-char identifier prefix so
    // the replacement preserves the prefix instead of stripping the entire token.
    private val API_KEY_HEADER: Pattern =
        Pattern.compile("(?i)(X-API-Key:\\s*)(pk_[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+")

    // Bearer eyJ… JWT — keep "Bearer ", strip rest
    private val JWT_BEARER: Pattern =
        Pattern.compile("(?i)(Bearer\\s+)eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    // Standalone JWS (eyJ header.payload.signature) without "Bearer " prefix —
    // catches license tokens and any other raw JWS accidentally logged.
    private val JWS_STANDALONE: Pattern =
        Pattern.compile("(?<![A-Za-z0-9_/-])eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    // pk_ + 8 base64url + tail → keep prefix (11 chars), strip secret tail
    private val API_KEY: Pattern =
        Pattern.compile("pk_[A-Za-z0-9_-]{8}[A-Za-z0-9_-]+")

    // password="xxx" or password=xxx
    private val PASSWORD_KV: Pattern =
        Pattern.compile("(?i)(password)\\s*=\\s*\"?[^\\s\"\\]]+\"?")

    // bcrypt hash $2a$/$2b$/$2y$ (62 chars total: $2x$NN$ + 53 chars)
    private val BCRYPT: Pattern =
        Pattern.compile("\\$2[ayb]\\$\\d{2}\\$[A-Za-z0-9./]{53}")

    // JSON 토큰 필드 값 마스킹 (authenticationToken/registrationToken/regRelayToken).
    // 본문이 로그 문자열 안에 박혀 \" 로 이스케이프된 경우도 포함. 값만 가리고 필드명은 남긴다.
    private val JSON_TOKEN_FIELD: Pattern =
        Pattern.compile("(\\\\?\"(?:authenticationToken|registrationToken|regRelayToken)\\\\?\"\\s*:\\s*\\\\?\")[^\"\\\\]+")

    fun redact(msg: String?): String? {
        if (msg == null || msg.isEmpty()) return msg

        // Order matters: header-form first (it embeds the pk_ token), then JWT,
        // then standalone JWS, then standalone pk_, then password and bcrypt.
        var result = API_KEY_HEADER.matcher(msg).replaceAll("\$1\$2<redacted>")
        result = JWT_BEARER.matcher(result).replaceAll("\$1<redacted>")
        result = JWS_STANDALONE.matcher(result).replaceAll("<jws-redacted>")
        result = API_KEY.matcher(result).replaceAll { m ->
            val full = m.group()
            full.substring(0, 11) + "<redacted>" // pk_ + 8 + <redacted>
        }
        result = PASSWORD_KV.matcher(result).replaceAll("\$1=<redacted>")
        result = BCRYPT.matcher(result).replaceAll("<bcrypt-redacted>")
        return JSON_TOKEN_FIELD.matcher(result).replaceAll("\$1<redacted>")
    }
}
