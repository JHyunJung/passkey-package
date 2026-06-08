package com.crosscert.passkey.webauthn.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeAuthenticationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeWebAuthnVerifier verifier = NativeWebAuthnVerifier.withDefaults(mapper);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void verifiesEs256Assertion() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "auth-challenge".getBytes(StandardCharsets.UTF_8);

        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] cose = cose(pub);

        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        ByteArrayOutputStream ad = new ByteArrayOutputStream();
        ad.writeBytes(rpIdHash);
        ad.write(0x05); // UP + UV
        ad.writeBytes(new byte[]{0, 0, 0, 5});
        byte[] authData = ad.toByteArray();

        String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] clientDataBytes = clientData.getBytes(StandardCharsets.UTF_8);
        byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes);

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(authData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        byte[] credId = new byte[]{1, 2, 3};
        String credJson = "{\"id\":\"" + B64.encodeToString(credId) + "\",\"rawId\":\""
                + B64.encodeToString(credId) + "\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientDataBytes) + "\","
                + "\"authenticatorData\":\"" + B64.encodeToString(authData) + "\","
                + "\"signature\":\"" + B64.encodeToString(signature) + "\"}}";

        StoredCredential stored = new StoredCredential(credId, cose, 4);
        AuthenticationInput input = new AuthenticationInput(
                credJson, challenge, Set.of(origin), rpId, true, stored);

        AuthenticationResult result = verifier.verifyAuthentication(input);

        assertArrayEquals(credId, result.credentialId());
        assertEquals(5L, result.newSignCount());
        assertTrue(result.uvVerified());
    }

    @Test
    void rejectsBadSignature() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "c".getBytes();
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey pub = (ECPublicKey) g.generateKeyPair().getPublic();
        byte[] cose = cose(pub);

        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        ByteArrayOutputStream ad = new ByteArrayOutputStream();
        ad.writeBytes(rpIdHash);
        ad.write(0x01);
        ad.writeBytes(new byte[]{0, 0, 0, 1});
        byte[] authData = ad.toByteArray();
        String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";

        byte[] credId = new byte[]{9};
        String credJson = "{\"id\":\"CQ\",\"rawId\":\"CQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"authenticatorData\":\"" + B64.encodeToString(authData) + "\","
                + "\"signature\":\"" + B64.encodeToString(new byte[64]) + "\"}}";

        StoredCredential stored = new StoredCredential(credId, cose, 0);
        AuthenticationInput input = new AuthenticationInput(
                credJson, challenge, Set.of(origin), rpId, false, stored);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyAuthentication(input));
        assertEquals(WebAuthnVerificationException.Reason.BAD_SIGNATURE, ex.reason());
    }

    @Test
    void rejectsWrongRpIdHash() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "c".getBytes();
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        byte[] cose = cose((ECPublicKey) kp.getPublic());

        // authData with WRONG rpIdHash
        byte[] wrongHash = MessageDigest.getInstance("SHA-256").digest("evil.com".getBytes());
        ByteArrayOutputStream ad = new ByteArrayOutputStream();
        ad.writeBytes(wrongHash);
        ad.write(0x01);
        ad.writeBytes(new byte[]{0, 0, 0, 1});
        byte[] authData = ad.toByteArray();
        String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientData.getBytes());
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(authData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        byte[] credId = new byte[]{9};
        String credJson = "{\"id\":\"CQ\",\"rawId\":\"CQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"authenticatorData\":\"" + B64.encodeToString(authData) + "\","
                + "\"signature\":\"" + B64.encodeToString(signature) + "\"}}";

        StoredCredential stored = new StoredCredential(credId, cose, 0);
        AuthenticationInput input = new AuthenticationInput(
                credJson, challenge, Set.of(origin), rpId, false, stored);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyAuthentication(input));
        assertEquals(WebAuthnVerificationException.Reason.RP_ID_HASH_MISMATCH, ex.reason());
    }

    @Test
    void rejectsCredentialIdMismatch() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        byte[] cose = cose((ECPublicKey) kp.getPublic());

        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        ByteArrayOutputStream ad = new ByteArrayOutputStream();
        ad.writeBytes(rpIdHash);
        ad.write(0x01);
        ad.writeBytes(new byte[]{0, 0, 0, 1});
        byte[] authData = ad.toByteArray();
        String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientData.getBytes());
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(authData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        // assertion의 rawId는 {7,7,7} 인데 stored는 {1,2,3} → 불일치
        byte[] assertedId = new byte[]{7, 7, 7};
        byte[] storedId = new byte[]{1, 2, 3};
        String credJson = "{\"id\":\"" + B64.encodeToString(assertedId) + "\",\"rawId\":\""
                + B64.encodeToString(assertedId) + "\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"authenticatorData\":\"" + B64.encodeToString(authData) + "\","
                + "\"signature\":\"" + B64.encodeToString(signature) + "\"}}";

        StoredCredential stored = new StoredCredential(storedId, cose, 0);
        AuthenticationInput input = new AuthenticationInput(
                credJson, challenge, Set.of(origin), rpId, false, stored);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyAuthentication(input));
        assertEquals(WebAuthnVerificationException.Reason.MALFORMED_INPUT, ex.reason());
    }

    private static byte[] cose(ECPublicKey pub) {
        byte[] x = fixed32(pub.getW().getAffineX());
        byte[] y = fixed32(pub.getW().getAffineY());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa5);
        o.write(0x01); o.write(0x02);
        o.write(0x03); o.write(0x26);
        o.write(0x20); o.write(0x01);
        o.write(0x21); o.write(0x58); o.write(0x20); o.writeBytes(x);
        o.write(0x22); o.write(0x58); o.write(0x20); o.writeBytes(y);
        return o.toByteArray();
    }

    private static byte[] fixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
