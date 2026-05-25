package com.crosscert.passkey.core.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SigningKeyProviderTest {

    private final SigningKeyProvider provider = new SigningKeyProvider();

    @Test
    void initGeneratesRsa2048KeyPairWithKid() throws Exception {
        provider.init();
        RSAKey key = provider.signingKey();
        assertThat(key.getKeyID()).isNotBlank();
        assertThat(key.size()).isEqualTo(2048);
        assertThat(key.isPrivate()).isTrue();
    }

    @Test
    void publicJwkSetOmitsPrivateMaterial() throws Exception {
        provider.init();
        JWKSet publicSet = provider.publicJwkSet();
        assertThat(publicSet.getKeys()).hasSize(1);
        assertThat(publicSet.getKeys().get(0).isPrivate()).isFalse();
    }

    @Test
    void kidIsDeterministicAcrossReadsButChangesBetweenInits() throws Exception {
        provider.init();
        String kid1 = provider.signingKey().getKeyID();
        String kidAgain = provider.signingKey().getKeyID();
        assertThat(kidAgain).isEqualTo(kid1);

        SigningKeyProvider other = new SigningKeyProvider();
        other.init();
        assertThat(other.signingKey().getKeyID()).isNotEqualTo(kid1);
    }
}
