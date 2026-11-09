package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.jwt.KeyEnvelope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64

class MfaSecretCipherTest {

    private fun cipher(): MfaSecretCipher {
        val master = ByteArray(32)
        SecureRandom().nextBytes(master)
        val env = KeyEnvelope(Base64.getEncoder().encodeToString(master), SecureRandom())
        return MfaSecretCipher(env)
    }

    @Test
    fun seal_then_open_roundtrips() {
        val c = cipher()
        val secret = "JBSWY3DPEHPK3PXP"
        val sealed = c.seal(secret)
        assertThat(sealed).startsWith("enc:v1:")
        assertThat(sealed).doesNotContain(secret) // 평문 미노출
        assertThat(c.open(sealed)).isEqualTo(secret)
    }

    @Test
    fun open_passes_through_legacy_plaintext() {
        val c = cipher()
        assertThat(c.open("JBSWY3DPEHPK3PXP")).isEqualTo("JBSWY3DPEHPK3PXP")
    }

    @Test
    fun open_null_returns_null() {
        assertThat(cipher().open(null)).isNull()
    }

    @Test
    fun open_corrupt_ciphertext_fails_generically() {
        val c = cipher()
        val corrupt = "enc:v1:" + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertThatThrownBy { c.open(corrupt) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("mfa secret open failed")
            .hasNoCause()
    }
}
