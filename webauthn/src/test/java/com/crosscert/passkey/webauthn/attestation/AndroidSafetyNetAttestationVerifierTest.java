package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fmt=android-safetynet (WebAuthn §8.5, Android SafetyNet Attestation) 검증기 테스트.
 *
 * <p>android 기기/SafetyNet 서버 없이 BouncyCastle로 충실한 SafetyNet JWS를 합성한다:
 * <ul>
 *   <li>attStmt = {@code { ver: text, response: bytes }}, response = JWS compact (header.payload.sig)</li>
 *   <li>header = {@code {"alg":"RS256","x5c":["<base64 leaf DER>"]}}</li>
 *   <li>payload = {@code {"nonce":"<base64Std(SHA-256(authData||clientDataHash))>","ctsProfileMatch":true,...}}</li>
 *   <li>leaf cert subject = CN=attest.android.com (verifier가 CN + JWS 서명을 leaf 공개키로 검증)</li>
 * </ul>
 *
 * 핵심 성질:
 *  - JWS 서명이 (header64.payload64)에 대한 leaf 공개키 유효 서명
 *  - payload.nonce == base64Std(SHA-256(authData||clientDataHash))
 *  - leaf subject CN == attest.android.com
 *  - payload.ctsProfileMatch == true
 */
class AndroidSafetyNetAttestationVerifierTest {

    private final AndroidSafetyNetAttestationVerifier verifier = new AndroidSafetyNetAttestationVerifier();

    @Test
    void formatIsAndroidSafetynet() {
        assertEquals("android-safetynet", verifier.format());
    }

    @Test
    void acceptsValidSafetyNetRs256() throws Exception {
        Fixture f = new Fixture();
        byte[] response = f.buildRs256Jws("attest.android.com", true, /* tamperSig */ false, /* wrongNonce */ false);
        CborMap attStmt = attStmt("17612000", response);

        AttestationResult r = verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash);
        assertEquals(AttestationResult.Type.BASIC, r.type());
        assertEquals(1, r.trustPath().size());
    }

    @Test
    void acceptsValidSafetyNetRs256ResponseAsCborText() throws Exception {
        // 일부 인코더는 response를 text string으로 담는다 → CborText fallback 경로 검증.
        Fixture f = new Fixture();
        byte[] response = f.buildRs256Jws("attest.android.com", true, false, false);
        String responseStr = new String(response, StandardCharsets.UTF_8);
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("ver"), new CborText("17612000"));
        m.put(new CborText("response"), new CborText(responseStr));
        CborMap attStmt = new CborMap(m);

        AttestationResult r = verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash);
        assertEquals(AttestationResult.Type.BASIC, r.type());
    }

    @Test
    void acceptsValidSafetyNetEs256() throws Exception {
        Fixture f = new Fixture();
        byte[] response = f.buildEs256Jws("attest.android.com", true);
        CborMap attStmt = attStmt("17612000", response);

        AttestationResult r = verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash);
        assertEquals(AttestationResult.Type.BASIC, r.type());
    }

    @Test
    void rejectsWrongNonce() throws Exception {
        Fixture f = new Fixture();
        byte[] response = f.buildRs256Jws("attest.android.com", true, false, /* wrongNonce */ true);
        CborMap attStmt = attStmt("17612000", response);

        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash));
    }

    @Test
    void rejectsTamperedJwsSignature() throws Exception {
        Fixture f = new Fixture();
        byte[] response = f.buildRs256Jws("attest.android.com", true, /* tamperSig */ true, false);
        CborMap attStmt = attStmt("17612000", response);

        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash));
    }

    @Test
    void rejectsCtsProfileMatchFalse() throws Exception {
        Fixture f = new Fixture();
        byte[] response = f.buildRs256Jws("attest.android.com", /* cts */ false, false, false);
        CborMap attStmt = attStmt("17612000", response);

        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash));
    }

    @Test
    void rejectsWrongHostname() throws Exception {
        Fixture f = new Fixture();
        byte[] response = f.buildRs256Jws("evil.example.com", true, false, false);
        CborMap attStmt = attStmt("17612000", response);

        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash));
    }

    @Test
    void rejectsCnSubstringBypass() throws Exception {
        // CN=evil-attest.android.com.example — substring은 포함하지만 정확 일치 아님 → 거부 (codex P1).
        // nonce/sig/ctsProfileMatch는 모두 유효하게 두어 CN 정확-일치 검사에서만 실패해야 함.
        Fixture f = new Fixture();
        byte[] response = f.buildRs256Jws("evil-attest.android.com.example", true, false, false);
        CborMap attStmt = attStmt("17612000", response);

        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash));
    }

    @Test
    void rejectsAlgLeafKeyMismatch() throws Exception {
        // header.alg=ES256 인데 leaf 가 RSA 키 → alg↔leaf 키타입 명시 핀에서 거부 (F32, 방어심화).
        // JCA 도 결국 막지만(signature invalid), 여기서는 명시 거부 경로를 검증한다.
        Fixture f = new Fixture();
        byte[] response = f.buildEs256HeaderWithRsaLeaf();
        CborMap attStmt = attStmt("17612000", response);

        AttestationException ex = assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash));
        // 명시 핀(alg/leaf-key mismatch)에서 거부되어야 함 — 일반 "signature invalid" 흡수가 아니라.
        assertTrue(ex.getMessage().contains("alg/leaf-key mismatch"),
                "expected alg/leaf-key mismatch rejection, got: " + ex.getMessage());
    }

    @Test
    void rejectsMalformedBase64Jws() throws Exception {
        // 비-base64url 3파트 response → AttestationException (IllegalArgumentException 누출 아님) (codex P2).
        Fixture f = new Fixture();
        byte[] response = "not!base64.also!bad.nope!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CborMap attStmt = attStmt("17612000", response);

        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, attStmt, f.clientDataHash));
    }

    // ---- attStmt / fixture ----

    private static CborMap attStmt(String ver, byte[] response) {
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("ver"), new CborText(ver));
        m.put(new CborText("response"), new CborBytes(response));
        return new CborMap(m);
    }

    /** authData + clientDataHash + 기대 nonce를 한 번에 만들어 두는 픽스처. */
    private static final class Fixture {
        final byte[] rawAuthData;
        final AuthenticatorData authData;
        final byte[] clientDataHash;
        final String expectedNonceB64;

        Fixture() throws Exception {
            // registration-style authData (AT 플래그 + ES256 COSE) — 충실도 위해 AT 포함.
            KeyPair credKp = ecKeyPair();
            ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
            byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
            this.rawAuthData = authDataWithCredential(cose);
            this.authData = AuthenticatorDataParser.parse(rawAuthData);

            this.clientDataHash = new byte[32];
            for (int i = 0; i < 32; i++) clientDataHash[i] = (byte) (i + 7);

            byte[] nonceInput = concat(rawAuthData, clientDataHash);
            byte[] nonce = sha256(nonceInput);
            this.expectedNonceB64 = Base64.getEncoder().encodeToString(nonce); // STANDARD base64
        }

        byte[] buildRs256Jws(String cn, boolean cts, boolean tamperSig, boolean wrongNonce) throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            X509Certificate leaf = selfSigned(kp, cn, "SHA256withRSA");

            String header = "{\"alg\":\"RS256\",\"x5c\":[\"" + b64Std(leaf.getEncoded()) + "\"]}";
            String payload = payloadJson(cts, wrongNonce);
            String signingInput = b64Url(header.getBytes(StandardCharsets.UTF_8))
                    + "." + b64Url(payload.getBytes(StandardCharsets.UTF_8));

            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(kp.getPrivate());
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] sig = s.sign();
            if (tamperSig) sig[sig.length - 1] ^= 0x01;

            String jws = signingInput + "." + b64Url(sig);
            return jws.getBytes(StandardCharsets.UTF_8);
        }

        byte[] buildEs256Jws(String cn, boolean cts) throws Exception {
            KeyPair kp = ecKeyPair();
            X509Certificate leaf = selfSigned(kp, cn, "SHA256withECDSA");

            String header = "{\"alg\":\"ES256\",\"x5c\":[\"" + b64Std(leaf.getEncoded()) + "\"]}";
            String payload = payloadJson(cts, false);
            String signingInput = b64Url(header.getBytes(StandardCharsets.UTF_8))
                    + "." + b64Url(payload.getBytes(StandardCharsets.UTF_8));

            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(kp.getPrivate());
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] derSig = s.sign();
            byte[] rawSig = derToRaw(derSig); // JWS ES256은 raw R||S 64바이트

            String jws = signingInput + "." + b64Url(rawSig);
            return jws.getBytes(StandardCharsets.UTF_8);
        }

        /**
         * header.alg=ES256 으로 선언하지만 leaf 는 RSA 키인 alg-confusion JWS (F32 핀 검증).
         * 서명은 RSA(SHA256withRSA)로 생성하나, alg↔leaf 키타입 핀이 서명 검증 이전에 거부한다.
         */
        byte[] buildEs256HeaderWithRsaLeaf() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            X509Certificate leaf = selfSigned(kp, "attest.android.com", "SHA256withRSA");

            String header = "{\"alg\":\"ES256\",\"x5c\":[\"" + b64Std(leaf.getEncoded()) + "\"]}";
            String payload = payloadJson(true, false);
            String signingInput = b64Url(header.getBytes(StandardCharsets.UTF_8))
                    + "." + b64Url(payload.getBytes(StandardCharsets.UTF_8));

            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(kp.getPrivate());
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] sig = s.sign();

            String jws = signingInput + "." + b64Url(sig);
            return jws.getBytes(StandardCharsets.UTF_8);
        }

        private String payloadJson(boolean cts, boolean wrongNonce) {
            String nonce = wrongNonce
                    ? Base64.getEncoder().encodeToString(new byte[32]) // 0으로 채운 잘못된 nonce
                    : expectedNonceB64;
            return "{\"nonce\":\"" + nonce + "\",\"timestampMs\":123,"
                    + "\"apkPackageName\":\"com.example\",\"ctsProfileMatch\":" + cts
                    + ",\"basicIntegrity\":true}";
        }
    }

    // ---- JWS / 인코딩 helpers ----

    private static String b64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String b64Std(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** JWS ES256 테스트 빌드: JDK가 만든 DER ECDSA 서명을 raw R||S(64바이트)로 변환 (JwsEcdsa.toDer의 역). */
    private static byte[] derToRaw(byte[] der) {
        // der = SEQUENCE { INTEGER r, INTEGER s }
        int idx = 0;
        if ((der[idx++] & 0xff) != 0x30) throw new IllegalStateException("not a DER SEQUENCE");
        int seqLen = der[idx++] & 0xff; // r/s 합은 짧으므로 단일바이트 길이
        if ((der[idx++] & 0xff) != 0x02) throw new IllegalStateException("expected INTEGER r");
        int rLen = der[idx++] & 0xff;
        BigInteger r = new BigInteger(Arrays.copyOfRange(der, idx, idx + rLen));
        idx += rLen;
        if ((der[idx++] & 0xff) != 0x02) throw new IllegalStateException("expected INTEGER s");
        int sLen = der[idx++] & 0xff;
        BigInteger s = new BigInteger(Arrays.copyOfRange(der, idx, idx + sLen));
        byte[] out = new byte[64];
        byte[] rb = fixed32(r);
        byte[] sb = fixed32(s);
        System.arraycopy(rb, 0, out, 0, 32);
        System.arraycopy(sb, 0, out, 32, 32);
        return out;
    }

    private static X509Certificate selfSigned(KeyPair kp, String cn, String sigAlg) throws Exception {
        org.bouncycastle.asn1.x500.X500Name name =
                new org.bouncycastle.asn1.x500.X500Name("CN=" + cn);
        var builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(42),
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                name, kp.getPublic());
        org.bouncycastle.operator.ContentSigner signer =
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(sigAlg).build(kp.getPrivate());
        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static byte[] es256Cose(byte[] x, byte[] y) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa5);
        o.write(0x01); o.write(0x02);
        o.write(0x03); o.write(0x26);
        o.write(0x20); o.write(0x01);
        o.write(0x21); o.write(0x58); o.write(0x20); o.writeBytes(x);
        o.write(0x22); o.write(0x58); o.write(0x20); o.writeBytes(y);
        return o.toByteArray();
    }

    private static byte[] authDataWithCredential(byte[] cose) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[32]);                 // rpIdHash
        o.write(0x41);                              // UP + AT
        o.writeBytes(new byte[]{0, 0, 0, 0});       // signCount
        o.writeBytes(new byte[16]);                 // aaguid
        o.writeBytes(new byte[]{0, 4});             // credIdLen = 4
        o.writeBytes(new byte[]{1, 2, 3, 4});       // credId
        o.writeBytes(cose);                         // COSE_Key
        return o.toByteArray();
    }

    private static byte[] fixed32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] sha256(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
