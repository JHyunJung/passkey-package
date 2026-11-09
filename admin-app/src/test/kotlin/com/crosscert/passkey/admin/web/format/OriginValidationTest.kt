package com.crosscert.passkey.admin.web.format

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 원본: WebauthnConfigTab.test.ts (validateOrigin 15). origin host 는 rpId 와 같거나 rpId 의 서브도메인이어야 한다. */
class OriginValidationTest {

    companion object {
        const val RP = "dev-passkey.crosscert.com"

        private fun v(input: String): OriginValidation.Result = OriginValidation.validate(input, RP)

        private fun ok(input: String, expected: String) {
            val r = v(input)
            assertThat(r.ok).`as`(input).isTrue()
            assertThat(r.value).isEqualTo(expected)
        }
    }

    @Test @DisplayName("rpId 자기 자신을 허용하고 https origin 으로 정규화한다")
    fun self() { ok("https://dev-passkey.crosscert.com", "https://dev-passkey.crosscert.com") }

    @Test @DisplayName("스킴 없이 host 만 입력하면 https:// 를 가정한다")
    fun noScheme() { ok("dev-passkey.crosscert.com", "https://dev-passkey.crosscert.com") }

    @Test @DisplayName("서브도메인을 허용한다")
    fun sub() { ok("sub.dev-passkey.crosscert.com", "https://sub.dev-passkey.crosscert.com") }

    @Test @DisplayName("여러 단계 서브도메인도 허용한다")
    fun deepSub() { assertThat(v("a.b.dev-passkey.crosscert.com").ok).isTrue() }

    @Test @DisplayName("port 가 있어도 host 가 맞으면 허용하고 port 를 보존한다")
    fun port() { ok("https://dev-passkey.crosscert.com:8443", "https://dev-passkey.crosscert.com:8443") }

    @Test @DisplayName("경로·쿼리는 제거하고 scheme://host 로 정규화한다")
    fun stripsPathAndQuery() { ok("https://sub.dev-passkey.crosscert.com/login?x=1", "https://sub.dev-passkey.crosscert.com") }

    @Test @DisplayName("rpId 와 비슷하지만 다른 host 는 거부한다 (de-passkey ≠ dev-passkey)")
    fun similar() { assertThat(v("de-passkey.crosscert.com").ok).isFalse() }

    @Test @DisplayName("상위 도메인은 거부한다 (rpId 보다 넓음)")
    fun parent() { assertThat(v("crosscert.com").ok).isFalse() }

    @Test @DisplayName("rpId 를 부분 문자열로만 포함하는 host(접미사 위장)는 거부한다")
    fun suffixDisguise() { assertThat(v("evil-dev-passkey.crosscert.com").ok).isFalse() }

    @Test @DisplayName("rpId 를 다른 도메인의 서브로 위장한 host 는 거부한다")
    fun attackerSuffix() { assertThat(v("dev-passkey.crosscert.com.attacker.com").ok).isFalse() }

    @Test @DisplayName("빈 입력은 거부한다")
    fun blank() {
        val r = v("   ")
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo("origin 을 입력하세요.")
    }

    @Test @DisplayName("대소문자를 구분하지 않는다")
    fun caseInsensitive() { ok("SUB.Dev-Passkey.Crosscert.COM", "https://sub.dev-passkey.crosscert.com") }

    @Test @DisplayName("이미 완성된 android:apk-key-hash origin 은 rpId 범위와 무관하게 통과한다")
    fun apk() {
        val apk = "android:apk-key-hash:" + "A".repeat(43)
        ok(apk, apk)
    }

    @Test @DisplayName("android:apk-key-hash 가 43자가 아니면 거부한다")
    fun apkShort() { assertThat(v("android:apk-key-hash:TOOSHORT").ok).isFalse() }

    @Test @DisplayName("android:apk-key-hash 가 43자라도 base64url 이 아닌 문자를 포함하면 거부한다")
    fun apkBadChar() { assertThat(v("android:apk-key-hash:" + "A".repeat(42) + "!").ok).isFalse() }

    @Test @DisplayName("추가: 형식 오류·범위 밖 문구, 기본 포트는 정규화에서 뺀다, 범위 밖 포트·깨진 입력은 거부")
    fun extras() {
        assertThat(v("evil.com").error).isEqualTo("origin host 'evil.com' 는 rpId '" + RP + "' 범위를 벗어납니다. rpId 자신이거나 그 서브도메인(예: sub." + RP + ")만 추가할 수 있습니다.")
        assertThat(v("bad host").error).startsWith("'bad host' 는 올바른 origin 형식이 아닙니다. 예) https://" + RP)
        ok("https://dev-passkey.crosscert.com:443", "https://dev-passkey.crosscert.com")
        assertThat(v("https://dev-passkey.crosscert.com:99999").ok).isFalse()
        assertThat(v("http://dev-passkey.crosscert.com:80/x").value).isEqualTo("http://dev-passkey.crosscert.com")
    }
}
