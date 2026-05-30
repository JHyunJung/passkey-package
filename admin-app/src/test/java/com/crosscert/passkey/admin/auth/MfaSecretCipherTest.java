package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.jwt.KeyEnvelope;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MfaSecretCipherTest {

    private MfaSecretCipher cipher() {
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        KeyEnvelope env = new KeyEnvelope(Base64.getEncoder().encodeToString(master),
                new SecureRandom());
        return new MfaSecretCipher(env);
    }

    @Test
    void seal_then_open_roundtrips() {
        MfaSecretCipher c = cipher();
        String secret = "JBSWY3DPEHPK3PXP";
        String sealed = c.seal(secret);
        assertThat(sealed).startsWith("enc:v1:");
        assertThat(sealed).doesNotContain(secret); // 평문 미노출
        assertThat(c.open(sealed)).isEqualTo(secret);
    }

    @Test
    void open_passes_through_legacy_plaintext() {
        MfaSecretCipher c = cipher();
        assertThat(c.open("JBSWY3DPEHPK3PXP")).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void open_null_returns_null() {
        assertThat(cipher().open(null)).isNull();
    }

    @Test
    void open_corrupt_ciphertext_fails_generically() {
        MfaSecretCipher c = cipher();
        String corrupt = "enc:v1:" + java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.open(corrupt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mfa secret open failed")
                .hasNoCause();
    }
}
