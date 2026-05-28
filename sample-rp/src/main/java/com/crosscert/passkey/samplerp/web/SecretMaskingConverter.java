package com.crosscert.passkey.samplerp.web;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Pattern-based defense-in-depth redaction applied to every formatted log
 * message. If a developer accidentally emits a raw API key, JWT, or password
 * in log.info, this converter strips the secret tail at output time.
 *
 * <p>Used as a custom MessageConverter in logback-spring.xml:
 * <pre>
 *   &lt;conversionRule conversionWord="msg" converterClass="...SecretMaskingConverter"/&gt;
 * </pre>
 * which overrides the default %msg behavior across all CONSOLE output.
 *
 * <p>Sample-rp copy of {@code com.crosscert.passkey.core.logging.SecretMaskingConverter}
 * — sample-rp does not depend on :core, so the class is duplicated here. Keep
 * the two in sync.
 */
public class SecretMaskingConverter extends MessageConverter {

    // X-API-Key: pk_… header form (apply BEFORE standalone pk_ pattern).
    // Capture both the header name AND the pk_ + 8-char identifier prefix so
    // the replacement preserves the prefix instead of stripping the entire token.
    private static final Pattern API_KEY_HEADER = Pattern.compile("(?i)(X-API-Key:\\s*)(pk_[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+");
    // Bearer eyJ… JWT — keep "Bearer ", strip rest
    private static final Pattern JWT_BEARER = Pattern.compile("(?i)(Bearer\\s+)eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    // pk_ + 8 base64url + tail → keep prefix (11 chars), strip secret tail
    private static final Pattern API_KEY = Pattern.compile("pk_[A-Za-z0-9_-]{8}[A-Za-z0-9_-]+");
    // password="xxx" or password=xxx
    private static final Pattern PASSWORD_KV = Pattern.compile("(?i)(password)\\s*=\\s*\"?[^\\s\"\\]]+\"?");
    // bcrypt hash $2a$/$2b$/$2y$ (62 chars total: $2x$NN$ + 53 chars)
    private static final Pattern BCRYPT = Pattern.compile("\\$2[ayb]\\$\\d{2}\\$[A-Za-z0-9./]{53}");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = super.convert(event);
        if (msg == null || msg.isEmpty()) return msg;

        // Order matters: header-form first (it embeds the pk_ token), then JWT,
        // then standalone pk_, then password and bcrypt.
        msg = API_KEY_HEADER.matcher(msg).replaceAll("$1$2<redacted>");
        msg = JWT_BEARER.matcher(msg).replaceAll("$1<redacted>");
        msg = API_KEY.matcher(msg).replaceAll(m -> {
            String full = m.group();
            return full.substring(0, 11) + "<redacted>";  // pk_ + 8 + <redacted>
        });
        msg = PASSWORD_KV.matcher(msg).replaceAll("$1=<redacted>");
        msg = BCRYPT.matcher(msg).replaceAll("<bcrypt-redacted>");

        return msg;
    }
}
