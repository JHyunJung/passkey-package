package com.crosscert.passkey.core.license;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Test-only license token issuer. Loads the Ed25519 test keypair
 * from classpath and signs JWS tokens used by LicenseVerifierTest,
 * LicenseStateMachineTest, and integration tests.
 *
 * THIS IS TEST CODE — production license issuance happens out-of-band.
 */
public final class LicenseTestFixtures {

    private static final String ISSUER = "license.crosscert.com";
    private static final String AUDIENCE = "passkey-onprem";
    private static final String TEST_TENANT = "00000000-0000-0000-0000-000000000001";

    private LicenseTestFixtures() {}

    public static String issueValid(Duration validFor, List<String> features) {
        return issue("lic-test-" + System.nanoTime(),
                Instant.now(), Instant.now(), Instant.now().plus(validFor),
                features, defaultLimits());
    }

    public static String issueValidWithLimits(Duration validFor,
                                              List<String> features,
                                              Map<String, Integer> limits) {
        return issue("lic-test-" + System.nanoTime(),
                Instant.now(), Instant.now(), Instant.now().plus(validFor),
                features, limits);
    }

    public static String issueExpired() {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        return issue("lic-test-expired",
                past.minus(Duration.ofDays(30)),
                past.minus(Duration.ofDays(30)),
                past,
                List.of("mds"), defaultLimits());
    }

    public static String issueExpiringSoon(int days, List<String> features) {
        Instant exp = Instant.now().plus(Duration.ofDays(days));
        return issue("lic-test-soon",
                Instant.now().minus(Duration.ofDays(360)),
                Instant.now().minus(Duration.ofDays(360)),
                exp, features, defaultLimits());
    }

    public static String issueWithBadSignature() throws Exception {
        String valid = issueValid(Duration.ofDays(30), List.of("mds"));
        // Flip the last byte of the signature to corrupt it.
        String[] parts = valid.split("\\.");
        byte[] sig = Base64URL.from(parts[2]).decode();
        sig[sig.length - 1] ^= 0x01;
        parts[2] = Base64URL.encode(sig).toString();
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private static Map<String, Integer> defaultLimits() {
        return Map.of("warningDaysBeforeExpiry", 30, "graceHoursWhenOffline", 72);
    }

    private static String issue(String jti, Instant iat, Instant nbf, Instant exp,
                                List<String> features, Map<String, Integer> limits) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject("test-customer")
                    .audience(AUDIENCE)
                    .issueTime(Date.from(iat))
                    .notBeforeTime(Date.from(nbf))
                    .expirationTime(Date.from(exp))
                    .jwtID(jti)
                    .claim("tenantId", TEST_TENANT)
                    .claim("features", features)
                    .claim("limits", limits)
                    .build();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA).build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new Ed25519Signer(loadPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test license", e);
        }
    }

    private static OctetKeyPair loadPrivateKey() {
        try (InputStream pubIn = new ClassPathResource("license-public.test.ed25519.pub").getInputStream();
             InputStream prvIn = new ClassPathResource("license-private.test.ed25519.pem").getInputStream()) {
            byte[] pubDer = Base64.getDecoder().decode(stripPem(new String(pubIn.readAllBytes())));
            byte[] prvDer = Base64.getDecoder().decode(stripPem(new String(prvIn.readAllBytes())));
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            EdECPublicKey pub = (EdECPublicKey) kf.generatePublic(new X509EncodedKeySpec(pubDer));
            EdECPrivateKey prv = (EdECPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(prvDer));
            byte[] rawX = encodePub(pub);
            byte[] rawD = prv.getBytes().orElseThrow();
            return new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(rawX))
                    .d(Base64URL.encode(rawD))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load test keypair", e);
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
    private static byte[] encodePub(EdECPublicKey key) {
        byte[] y = bigIntegerToLE(key.getPoint().getY(), 32);
        if (key.getPoint().isXOdd()) y[31] |= (byte) 0x80;
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
