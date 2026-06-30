package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

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
 * <p>Redaction logic is shared with JSON output via {@link SecretRedactor} —
 * both text and JSON paths call the same {@code redact()} function.
 *
 * <p>Sample-rp copy of {@code com.crosscert.passkey.core.logging.SecretMaskingConverter}
 * — rp-app does not depend on :core, so the class is duplicated here. Keep
 * the two in sync.
 */
public class SecretMaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedactor.redact(super.convert(event));
    }
}
