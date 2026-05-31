package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.entity.SigningKey;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Single factory for fresh RS256 signing-key material. Extracted from
 * the byte-identical keygen previously duplicated in
 * {@code SigningKeyProvider.generate()} and
 * {@code KeyRotationService.generateFreshActive()}.
 *
 * <p>Output contract (must stay invariant — signing-key wire format):
 * RSA-2048, KeyUse.SIGNATURE, JWSAlgorithm.RS256, kid = RFC7638
 * thumbprint, publicJwk = toPublicJWK().toJSONString(), sealed =
 * envelope.seal(PKCS8 private bytes).
 */
public final class SigningKeyFactory {

    private SigningKeyFactory() {}

    public static SigningKey newRsaSigningKey(KeyEnvelope envelope) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();

            RSAKey withoutKid = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            String kid = withoutKid.computeThumbprint().toString();
            RSAKey rsa = new RSAKey.Builder(withoutKid).keyID(kid).build();
            String publicJwk = rsa.toPublicJWK().toJSONString();
            byte[] sealed = envelope.seal(pair.getPrivate().getEncoded());
            return new SigningKey(kid, "RS256", publicJwk, sealed);
        } catch (Exception e) {
            throw new IllegalStateException("signing key generation failed", e);
        }
    }
}
