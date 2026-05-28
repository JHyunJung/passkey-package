package com.crosscert.passkey.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link SecretMaskingConverter} masks all 5 secret patterns and
 * that {@link LogRedact} helpers return the expected redaction shape.
 *
 * <p>Calls the converter directly (no logback pipeline) — that's the simplest
 * surface that still exercises the patterns end-to-end. The plan permits this
 * autonomous simplification over ListAppender plumbing.
 */
class SecretMaskingConverterTest {

    @Test
    void redactApiKeyInHeader() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        String out = conv.convert(makeEvent("attempting X-API-Key: pk_devacme0longsecretvalue"));
        assertThat(out).doesNotContain("longsecretvalue");
        assertThat(out).contains("<redacted>");
        assertThat(out).contains("pk_devacme0");
    }

    @Test
    void redactStandalonePkPrefix() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        String out = conv.convert(makeEvent("token=pk_devacme0moresecret"));
        assertThat(out).contains("pk_devacme0<redacted>");
        assertThat(out).doesNotContain("moresecret");
    }

    @Test
    void redactBearerJwt() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        String out = conv.convert(makeEvent(
                "auth: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.AbCDeFg"));
        assertThat(out).contains("Bearer <redacted>");
        assertThat(out).doesNotContain("AbCDeFg");
    }

    @Test
    void redactPassword() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        String out = conv.convert(makeEvent("login: password=hunter2 user=alice"));
        assertThat(out).contains("password=<redacted>");
        assertThat(out).doesNotContain("hunter2");
    }

    @Test
    void redactBcryptHash() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        String out = conv.convert(makeEvent(
                "stored=$2a$12$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G"));
        assertThat(out).contains("<bcrypt-redacted>");
        assertThat(out).doesNotContain("Kk24Zbb1G");
    }

    @Test
    void logRedactHelpers() {
        assertThat(LogRedact.email("alice@crosscert.com")).isEqualTo("a***@crosscert.com");
        assertThat(LogRedact.apiKey("pk_devacme0longsecret")).isEqualTo("pk_devacme0");
        assertThat(LogRedact.token("eyJhbGciOiJSUzI1NiJ9.x.y")).isEqualTo("eyJhb…<redacted>");
        assertThat(LogRedact.idTail("ABCDEFGHIJKLMNO", 8)).isEqualTo("HIJKLMNO");
        assertThat(LogRedact.idTail("short", 8)).isEqualTo("***");
        assertThat(LogRedact.email(null)).isEqualTo("");
        assertThat(LogRedact.apiKey(null)).isEqualTo("<redacted>");
        assertThat(LogRedact.apiKey("pk_only11ch")).isEqualTo("<redacted>"); // exactly 11 chars
        assertThat(LogRedact.token(null)).isEqualTo("<redacted>");
        assertThat(LogRedact.idTail(null, 8)).isEqualTo("");
    }

    private static LoggingEvent makeEvent(String message) {
        LoggingEvent e = new LoggingEvent();
        e.setMessage(message);
        e.setLoggerName("test");
        e.setLevel(Level.INFO);
        return e;
    }
}
