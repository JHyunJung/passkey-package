package com.crosscert.passkey.core.logging;

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
 * <p>This is the second line of defense — the first is the source-level
 * {@link LogRedact} helper which developers should call directly. See
 * docs/logging-conventions.md.
 */
public class SecretMaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedactor.redact(super.convert(event));
    }
}
