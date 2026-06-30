package com.crosscert.passkey.rpapp.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rp-app SecretRedactor의 마스킹 패턴을 고정하는 회귀 테스트.
 * SecretRedactor는 :core에 의존하지 않는 독립 object이므로 여기서 직접 검증한다.
 *
 * 검증 순서: API-Key 헤더 → Bearer JWT → Standalone JWS → Standalone pk_ → password → bcrypt → 무해 메시지 → null/empty
 */
class SecretRedactorTest {

    // ── 1. X-API-Key 헤더 형식 ────────────────────────────────────────────────

    @Test
    void redactsApiKeyInXApiKeyHeader() {
        String out = SecretRedactor.redact("attempting X-API-Key: pk_devacme0longsecretvalue");
        assertThat(out).contains("pk_devacme0");     // pk_ + 8-char prefix preserved
        assertThat(out).contains("<redacted>");
        assertThat(out).doesNotContain("longsecretvalue");
    }

    @Test
    void redactsApiKeyHeaderCaseInsensitive() {
        String out = SecretRedactor.redact("x-api-key: pk_abcd1234efgh5678IJKL");
        assertThat(out).contains("pk_abcd1234");
        assertThat(out).contains("<redacted>");
        assertThat(out).doesNotContain("efgh5678IJKL");
    }

    // ── 2. Bearer JWT ─────────────────────────────────────────────────────────

    @Test
    void redactsBearerJwt() {
        String out = SecretRedactor.redact(
            "auth: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.AbCDeFg"
        );
        assertThat(out).contains("Bearer <redacted>");
        assertThat(out).doesNotContain("eyJhbGciOiJSUzI1NiJ9");
        assertThat(out).doesNotContain("AbCDeFg");
    }

    @Test
    void redactsBearerJwtCaseInsensitivePrefix() {
        String out = SecretRedactor.redact("BEARER eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sigSIG");
        assertThat(out).contains("<redacted>");
        assertThat(out).doesNotContain("sigSIG");
    }

    // ── 3. Standalone JWS (새로 추가된 패턴) ─────────────────────────────────

    @Test
    void redactsStandaloneJwsWithoutBearerPrefix() {
        // raw JWS that is not preceded by "Bearer " — e.g. idToken or license token
        String out = SecretRedactor.redact(
            "idToken=eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sigSIGsig done"
        );
        assertThat(out).contains("<jws-redacted>");
        assertThat(out).doesNotContain("eyJhbGciOiJSUzI1NiJ9");
        assertThat(out).doesNotContain("sigSIGsig");
    }

    @Test
    void standaloneJwsPatternReplacesWithJwsRedactedMarker() {
        String jws = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.AbCDeFgHiJkLmNoPqRsT";
        String out = SecretRedactor.redact("license=" + jws + " end");
        assertThat(out).isEqualTo("license=<jws-redacted> end");
    }

    // ── 4. Standalone pk_ 토큰 ────────────────────────────────────────────────

    @Test
    void redactsStandalonePkApiKey() {
        String out = SecretRedactor.redact("token=pk_devacme0moresecret");
        assertThat(out).contains("pk_devacme0<redacted>");
        assertThat(out).doesNotContain("moresecret");
    }

    @Test
    void pkPrefixExactly11CharsPreserved() {
        // pk_ (3) + 8 identifier chars = 11 chars kept, rest redacted
        String out = SecretRedactor.redact("key pk_12345678SECRETPART end");
        assertThat(out).contains("pk_12345678<redacted>");
        assertThat(out).doesNotContain("SECRETPART");
    }

    // ── 5. password KV ────────────────────────────────────────────────────────

    @Test
    void redactsPasswordKeyValuePair() {
        String out = SecretRedactor.redact("login: password=hunter2 user=alice");
        assertThat(out).contains("password=<redacted>");
        assertThat(out).doesNotContain("hunter2");
    }

    @Test
    void redactsQuotedPasswordValue() {
        String out = SecretRedactor.redact("login: password=\"s3cr3t\" ok");
        assertThat(out).contains("password=<redacted>");
        assertThat(out).doesNotContain("s3cr3t");
    }

    // ── 6. bcrypt ─────────────────────────────────────────────────────────────

    @Test
    void redactsBcrypt2aHash() {
        // $2a$NN$ + exactly 53 base64-ish chars
        String out = SecretRedactor.redact(
            "stored=$2a$12$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G"
        );
        assertThat(out).contains("<bcrypt-redacted>");
        assertThat(out).doesNotContain("Kk24Zbb1G");
    }

    @Test
    void redactsBcrypt2bHash() {
        String out = SecretRedactor.redact(
            "hash=$2b$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        );
        assertThat(out).contains("<bcrypt-redacted>");
        assertThat(out).doesNotContain("N9qo8uLOickgx2");
    }

    // ── 7. 무해 메시지 — 무변경 통과 ─────────────────────────────────────────

    @Test
    void passesCleanMessageThroughUnchanged() {
        String clean = "register/complete ok userHandle=...abc123 status=200";
        assertThat(SecretRedactor.redact(clean)).isEqualTo(clean);
    }

    // ── 8. null / empty 처리 ──────────────────────────────────────────────────

    @Test
    void returnsNullForNullInput() {
        assertThat(SecretRedactor.redact(null)).isNull();
    }

    @Test
    void returnsEmptyStringForEmptyInput() {
        assertThat(SecretRedactor.redact("")).isEqualTo("");
    }

    // ── 9. JSON 토큰 필드 마스킹 ──────────────────────────────────────────────

    @Test
    void redactsAuthenticationTokenValueInPlainJson() {
        String input = "{\"authenticationToken\":\"abc.def.ghiLIVETOKEN\",\"x\":1}";
        String out = SecretRedactor.redact(input);
        assertThat(out).contains("\"authenticationToken\":\"<redacted>\"");
        assertThat(out).doesNotContain("LIVETOKEN");
        assertThat(out).contains("\"x\":1");
    }

    @Test
    void redactsRegistrationTokenValueInPlainJson() {
        String input = "{\"registrationToken\":\"regpayload.regsigSECRET\"}";
        String out = SecretRedactor.redact(input);
        assertThat(out).contains("\"registrationToken\":\"<redacted>\"");
        assertThat(out).doesNotContain("SECRET");
    }

    @Test
    void redactsRegRelayTokenValueInEscapedLogString() {
        // Body embedded in a log string has backslash-escaped quotes
        String input = "body={\\\"regRelayToken\\\":\\\"payloadpart.sigpartSECRET\\\"}";
        String out = SecretRedactor.redact(input);
        assertThat(out).doesNotContain("SECRET");
        assertThat(out).contains("regRelayToken");
    }

    @Test
    void doesNotRedactNonTokenJsonField() {
        String input = "{\"username\":\"alice\",\"registrationToken\":\"tok.enSECRET\"}";
        String out = SecretRedactor.redact(input);
        assertThat(out).contains("alice");    // non-token field untouched
        assertThat(out).doesNotContain("SECRET");
    }
}
