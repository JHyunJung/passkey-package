package com.crosscert.passkey.core.logging;

import java.util.regex.Pattern;

/**
 * 로그 메시지에서 비밀값(API key/JWT/password/bcrypt)을 마스킹하는 공유 헬퍼.
 * 텍스트 출력의 {@link SecretMaskingConverter} 와 JSON 출력의 RedactingMessageJsonProvider 가
 * 같은 로직을 공유해 drift 를 막는다.
 */
public final class SecretRedactor {

    // X-API-Key: pk_… header form (apply BEFORE standalone pk_ pattern).
    // Capture both the header name AND the pk_ + 8-char identifier prefix so
    // the replacement preserves the prefix instead of stripping the entire token.
    private static final Pattern API_KEY_HEADER = Pattern.compile("(?i)(X-API-Key:\\s*)(pk_[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+");
    // Bearer eyJ… JWT — keep "Bearer ", strip rest
    private static final Pattern JWT_BEARER = Pattern.compile("(?i)(Bearer\\s+)eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    // Standalone JWS (eyJ header.payload.signature) without "Bearer " prefix —
    // catches license tokens and any other raw JWS accidentally logged.
    private static final Pattern JWS_STANDALONE = Pattern.compile(
            "(?<![A-Za-z0-9_/-])eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    // pk_ + 8 base64url + tail → keep prefix (11 chars), strip secret tail
    private static final Pattern API_KEY = Pattern.compile("pk_[A-Za-z0-9_-]{8}[A-Za-z0-9_-]+");
    // password="xxx" or password=xxx
    private static final Pattern PASSWORD_KV = Pattern.compile("(?i)(password)\\s*=\\s*\"?[^\\s\"\\]]+\"?");
    // bcrypt hash $2a$/$2b$/$2y$ (62 chars total: $2x$NN$ + 53 chars)
    private static final Pattern BCRYPT = Pattern.compile("\\$2[ayb]\\$\\d{2}\\$[A-Za-z0-9./]{53}");
    // JSON 토큰 필드 값 마스킹: "authenticationToken":"...", "registrationToken":"...", "regRelayToken":"..."
    // 본문이 로그 문자열 안에 박혀 \" 로 이스케이프된 경우(\"key\":\"val\")도 포함. 값만 가리고
    // 필드명은 남겨 디버깅 시 '토큰 존재'는 보이게 한다.
    private static final Pattern JSON_TOKEN_FIELD = Pattern.compile(
            "(\\\\?\"(?:authenticationToken|registrationToken|regRelayToken)\\\\?\"\\s*:\\s*\\\\?\")[^\"\\\\]+");

    private SecretRedactor() {}

    public static String redact(String msg) {
        if (msg == null || msg.isEmpty()) return msg;

        // Order matters: header-form first (it embeds the pk_ token), then JWT,
        // then standalone JWS, then standalone pk_, then password and bcrypt.
        msg = API_KEY_HEADER.matcher(msg).replaceAll("$1$2<redacted>");
        msg = JWT_BEARER.matcher(msg).replaceAll("$1<redacted>");
        msg = JWS_STANDALONE.matcher(msg).replaceAll("<jws-redacted>");
        msg = API_KEY.matcher(msg).replaceAll(m -> m.group().substring(0, 11) + "<redacted>");
        msg = PASSWORD_KV.matcher(msg).replaceAll("$1=<redacted>");
        msg = BCRYPT.matcher(msg).replaceAll("<bcrypt-redacted>");
        return JSON_TOKEN_FIELD.matcher(msg).replaceAll("$1<redacted>");
    }
}
