package com.crosscert.passkey.core.jwt;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyEnvelopeTest {

    private static final String MASTER_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]);
    private final KeyEnvelope envelope =
            new KeyEnvelope(MASTER_KEY_B64, new SecureRandom());

    @Test
    void sealAndOpenRoundTripsPlaintext() {
        byte[] pkcs8 = "the-private-key-bytes".getBytes();
        byte[] sealed = envelope.seal(pkcs8);
        byte[] opened = envelope.open(sealed);
        assertThat(opened).isEqualTo(pkcs8);
    }

    @Test
    void sealedFormatIsNoncePlusCiphertextPlusTag() {
        byte[] pkcs8 = new byte[100];
        byte[] sealed = envelope.seal(pkcs8);
        assertThat(sealed).hasSize(12 + 100 + 16);
    }

    @Test
    void differentInvocationsProduceDifferentCiphertexts() {
        byte[] pkcs8 = "same-input".getBytes();
        byte[] a = envelope.seal(pkcs8);
        byte[] b = envelope.seal(pkcs8);
        assertThat(a).isNotEqualTo(b);
        assertThat(envelope.open(a)).isEqualTo(pkcs8);
        assertThat(envelope.open(b)).isEqualTo(pkcs8);
    }

    @Test
    void tamperedCiphertextThrows() {
        byte[] sealed = envelope.seal("secret".getBytes());
        sealed[sealed.length - 1] ^= 0x01;
        assertThatThrownBy(() -> envelope.open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("envelope authentication failed");
    }

    @Test
    void wrongMasterKeyThrows() {
        byte[] sealed = envelope.seal("secret".getBytes());
        String otherMaster = Base64.getEncoder().encodeToString(new byte[]{
                1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,
                17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32});
        KeyEnvelope other = new KeyEnvelope(otherMaster, new SecureRandom());
        assertThatThrownBy(() -> other.open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("envelope authentication failed");
    }

    @Test
    void wrongMasterKeyLengthRejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new KeyEnvelope(shortKey, new SecureRandom()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
