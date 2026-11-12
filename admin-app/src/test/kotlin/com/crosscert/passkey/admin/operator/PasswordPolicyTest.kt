package com.crosscert.passkey.admin.operator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/** BCrypt 는 72바이트를 넘으면 IllegalArgumentException(영어 원문)을 던진다 — 정책이 그 전에 한국어로 거른다. */
class PasswordPolicyTest {

    @Test
    fun acceptsBoundaries() {
        assertThat(PasswordPolicy.accepts("a".repeat(12))).isTrue()
        assertThat(PasswordPolicy.accepts("a".repeat(72))).isTrue()
        assertThat(PasswordPolicy.accepts("가".repeat(24))).isTrue() // 한글 3바이트 × 24 = 72
    }

    @Test
    fun rejectsTooShortTooLongAndNull() {
        assertThat(PasswordPolicy.accepts(null)).isFalse()
        assertThat(PasswordPolicy.accepts("a".repeat(11))).isFalse()
        assertThat(PasswordPolicy.accepts("a".repeat(73))).isFalse()
        assertThat(PasswordPolicy.accepts("가".repeat(25))).isFalse() // 25자지만 75바이트
        assertThat(PasswordPolicy.accepts("a".repeat(70) + "가")).isFalse() // 71자 · 73바이트
    }

    @Test
    fun everyAcceptedPasswordIsEncodableByBcrypt() {
        val enc = BCryptPasswordEncoder(4)
        for (pw in listOf("a".repeat(72), "가".repeat(24), "a".repeat(69) + "가")) {
            assertThat(PasswordPolicy.accepts(pw)).isTrue()
            assertThat(enc.encode(pw)).startsWith("$2a$")
        }
    }
}
