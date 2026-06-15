package com.crosscert.passkey.rpapp.web

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Pattern-based defense-in-depth redaction applied to every formatted log
 * message. If a developer accidentally emits a raw API key, JWT, or password
 * in log.info, this converter strips the secret tail at output time.
 *
 * Used as a custom MessageConverter in logback-spring.xml:
 * ```
 *   <conversionRule conversionWord="msg" converterClass="...SecretMaskingConverter"/>
 * ```
 * which overrides the default %msg behavior across all CONSOLE output.
 *
 * Redaction logic is shared with JSON output via [SecretRedactor] —
 * both text and JSON paths call the same `redact()` function.
 *
 * Sample-rp copy of `com.crosscert.passkey.core.logging.SecretMaskingConverter`
 * — rp-app does not depend on :core, so the class is duplicated here. Keep
 * the two in sync.
 */
class SecretMaskingConverter : MessageConverter() {

    override fun convert(event: ILoggingEvent): String? =
        SecretRedactor.redact(super.convert(event))
}
