package com.crosscert.passkey.rpapp.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * rp-app SecretRedactor의 마스킹 패턴을 고정하는 회귀 테스트.
 * SecretRedactor는 :core에 의존하지 않는 독립 object이므로 여기서 직접 검증한다.
 *
 * 검증 순서: API-Key 헤더 → Bearer JWT → Standalone JWS → Standalone pk_ → password → bcrypt → 무해 메시지 → null/empty
 */
class SecretRedactorTest {

    // ── 1. X-API-Key 헤더 형식 ────────────────────────────────────────────────

    @Test
    fun `redacts API key in X-API-Key header`() {
        val out = SecretRedactor.redact("attempting X-API-Key: pk_devacme0longsecretvalue")
        assertThat(out).contains("pk_devacme0")     // pk_ + 8-char prefix preserved
        assertThat(out).contains("<redacted>")
        assertThat(out).doesNotContain("longsecretvalue")
    }

    @Test
    fun `redacts API key header — case insensitive`() {
        val out = SecretRedactor.redact("x-api-key: pk_abcd1234efgh5678IJKL")
        assertThat(out).contains("pk_abcd1234")
        assertThat(out).contains("<redacted>")
        assertThat(out).doesNotContain("efgh5678IJKL")
    }

    // ── 2. Bearer JWT ─────────────────────────────────────────────────────────

    @Test
    fun `redacts Bearer JWT`() {
        val out = SecretRedactor.redact(
            "auth: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.AbCDeFg"
        )
        assertThat(out).contains("Bearer <redacted>")
        assertThat(out).doesNotContain("eyJhbGciOiJSUzI1NiJ9")
        assertThat(out).doesNotContain("AbCDeFg")
    }

    @Test
    fun `redacts Bearer JWT — case insensitive prefix`() {
        val out = SecretRedactor.redact("BEARER eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sigSIG")
        assertThat(out).contains("<redacted>")
        assertThat(out).doesNotContain("sigSIG")
    }

    // ── 3. Standalone JWS (새로 추가된 패턴) ─────────────────────────────────

    @Test
    fun `redacts standalone JWS without Bearer prefix`() {
        // raw JWS that is not preceded by "Bearer " — e.g. idToken or license token
        val out = SecretRedactor.redact(
            "idToken=eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sigSIGsig done"
        )
        assertThat(out).contains("<jws-redacted>")
        assertThat(out).doesNotContain("eyJhbGciOiJSUzI1NiJ9")
        assertThat(out).doesNotContain("sigSIGsig")
    }

    @Test
    fun `standalone JWS pattern replaces with jws-redacted marker`() {
        val jws = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.AbCDeFgHiJkLmNoPqRsT"
        val out = SecretRedactor.redact("license=$jws end")
        assertThat(out).isEqualTo("license=<jws-redacted> end")
    }

    // ── 4. Standalone pk_ 토큰 ────────────────────────────────────────────────

    @Test
    fun `redacts standalone pk_ API key`() {
        val out = SecretRedactor.redact("token=pk_devacme0moresecret")
        assertThat(out).contains("pk_devacme0<redacted>")
        assertThat(out).doesNotContain("moresecret")
    }

    @Test
    fun `pk_ prefix — exactly 11 chars preserved`() {
        // pk_ (3) + 8 identifier chars = 11 chars kept, rest redacted
        val out = SecretRedactor.redact("key pk_12345678SECRETPART end")
        assertThat(out).contains("pk_12345678<redacted>")
        assertThat(out).doesNotContain("SECRETPART")
    }

    // ── 5. password KV ────────────────────────────────────────────────────────

    @Test
    fun `redacts password key-value pair`() {
        val out = SecretRedactor.redact("login: password=hunter2 user=alice")
        assertThat(out).contains("password=<redacted>")
        assertThat(out).doesNotContain("hunter2")
    }

    @Test
    fun `redacts quoted password value`() {
        val out = SecretRedactor.redact("login: password=\"s3cr3t\" ok")
        assertThat(out).contains("password=<redacted>")
        assertThat(out).doesNotContain("s3cr3t")
    }

    // ── 6. bcrypt ─────────────────────────────────────────────────────────────

    @Test
    fun `redacts bcrypt 2a hash`() {
        // $2a$NN$ + exactly 53 base64-ish chars
        val out = SecretRedactor.redact(
            "stored=\$2a\$12\$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G"
        )
        assertThat(out).contains("<bcrypt-redacted>")
        assertThat(out).doesNotContain("Kk24Zbb1G")
    }

    @Test
    fun `redacts bcrypt 2b hash`() {
        val out = SecretRedactor.redact(
            "hash=\$2b\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        )
        assertThat(out).contains("<bcrypt-redacted>")
        assertThat(out).doesNotContain("N9qo8uLOickgx2")
    }

    // ── 7. 무해 메시지 — 무변경 통과 ─────────────────────────────────────────

    @Test
    fun `passes clean message through unchanged`() {
        val clean = "register/complete ok userHandle=...abc123 status=200"
        assertThat(SecretRedactor.redact(clean)).isEqualTo(clean)
    }

    // ── 8. null / empty 처리 ──────────────────────────────────────────────────

    @Test
    fun `returns null for null input`() {
        assertThat(SecretRedactor.redact(null)).isNull()
    }

    @Test
    fun `returns empty string for empty input`() {
        assertThat(SecretRedactor.redact("")).isEqualTo("")
    }

    // ── 9. JSON 토큰 필드 마스킹 ──────────────────────────────────────────────

    @Test
    fun `redacts authenticationToken value in plain JSON`() {
        val input = """{"authenticationToken":"abc.def.ghiLIVETOKEN","x":1}"""
        val out = SecretRedactor.redact(input)
        assertThat(out).contains(""""authenticationToken":"<redacted>"""")
        assertThat(out).doesNotContain("LIVETOKEN")
        assertThat(out).contains(""""x":1""")
    }

    @Test
    fun `redacts registrationToken value in plain JSON`() {
        val input = """{"registrationToken":"regpayload.regsigSECRET"}"""
        val out = SecretRedactor.redact(input)
        assertThat(out).contains(""""registrationToken":"<redacted>"""")
        assertThat(out).doesNotContain("SECRET")
    }

    @Test
    fun `redacts regRelayToken value in escaped log string`() {
        // Body embedded in a log string has backslash-escaped quotes
        val input = """body={\"regRelayToken\":\"payloadpart.sigpartSECRET\"}"""
        val out = SecretRedactor.redact(input)
        assertThat(out).doesNotContain("SECRET")
        assertThat(out).contains("regRelayToken")
    }

    @Test
    fun `does not redact non-token JSON field`() {
        val input = """{"username":"alice","registrationToken":"tok.enSECRET"}"""
        val out = SecretRedactor.redact(input)
        assertThat(out).contains("alice")    // non-token field untouched
        assertThat(out).doesNotContain("SECRET")
    }
}
