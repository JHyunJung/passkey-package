package com.crosscert.passkey.core.license;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the Ed25519 public key bundled with the jar
 * (license-public.ed25519.pub on the classpath) and exposes it as a
 * Nimbus OctetKeyPair for use by LicenseVerifier. Profile-aware:
 * test code overrides this with the test keypair via constructor.
 */
@Component
public class LicensePublicKeyProvider {

    private static final String DEFAULT_RESOURCE = "license-public.ed25519.pub";

    private final OctetKeyPair publicKey;

    public LicensePublicKeyProvider() {
        this(DEFAULT_RESOURCE);
    }

    /** Test-friendly ctor. */
    public LicensePublicKeyProvider(String classpathResource) {
        this.publicKey = load(classpathResource);
    }

    public OctetKeyPair publicKey() {
        return publicKey;
    }

    private static OctetKeyPair load(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            String pem = new String(in.readAllBytes());
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(der));
            EdECPublicKey ed = (EdECPublicKey) pub;
            byte[] raw = encodeRfc8032(ed);
            return new OctetKeyPair.Builder(Curve.Ed25519,
                    com.nimbusds.jose.util.Base64URL.encode(raw))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load license public key from classpath: " + resource, e);
        }
    }

    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                  .replaceAll("-----END [A-Z ]+-----", "")
                  .replaceAll("\\s", "");
    }

    /**
     * Encode an EdECPublicKey into its 32-byte RFC 8032 raw form
     * (little-endian y, with x's sign in MSB of byte 31).
     */
    private static byte[] encodeRfc8032(EdECPublicKey key) {
        byte[] y = bigIntegerToLE(key.getPoint().getY(), 32);
        if (key.getPoint().isXOdd()) {
            y[31] |= (byte) 0x80;
        }
        return y;
    }

    private static byte[] bigIntegerToLE(java.math.BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        int srcLen = Math.min(be.length, len);
        for (int i = 0; i < srcLen; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }
}
