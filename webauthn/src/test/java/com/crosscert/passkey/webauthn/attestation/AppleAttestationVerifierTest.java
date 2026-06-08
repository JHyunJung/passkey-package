package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fmt=apple (WebAuthn §8.8) 검증기 테스트.
 * webauthn4j-test에 apple authenticator가 없으므로 BouncyCastle로
 * nonce extension(OID 1.2.840.113635.100.8.2)을 담은 leaf cert를 직접 만든다.
 */
class AppleAttestationVerifierTest {

    private static final String APPLE_NONCE_OID = "1.2.840.113635.100.8.2";

    private final AppleAttestationVerifier verifier = new AppleAttestationVerifier();

    @Test
    void formatIsApple() {
        assertEquals("apple", verifier.format());
    }

    @Test
    void acceptsValidAppleAttestation() throws Exception {
        // 1) credential 키쌍 (이 키가 authData COSE 키 + cert subject 양쪽에 들어간다)
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));

        // 2) authData (AT 플래그 + credential COSE)
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        // 3) clientDataHash
        byte[] clientDataHash = new byte[32];
        for (int i = 0; i < 32; i++) clientDataHash[i] = (byte) i;

        // 4) nonce = SHA-256(authData || clientDataHash)
        byte[] nonce = sha256(concat(rawAuthData, clientDataHash));

        // 5) leaf cert: subject 공개키 = credential 키, nonce extension 포함
        X509Certificate leaf = makeAppleLeaf(credKp, nonce);

        // 6) attStmt = { x5c: [leaf] }  (alg/sig 없음)
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("x5c"), new CborArray(List.of(new CborBytes(leaf.getEncoded()))));
        CborMap attStmt = new CborMap(m);

        AttestationResult r = verifier.verify(authData, rawAuthData, attStmt, clientDataHash);
        assertEquals(AttestationResult.Type.ATT_CA, r.type());
        assertEquals(1, r.trustPath().size());
    }

    @Test
    void rejectsWrongNonce() throws Exception {
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        // cert에는 clientDataHash=0 기준 nonce를 굽고, 검증은 다른 clientDataHash로 호출
        byte[] bakedHash = new byte[32];
        byte[] nonce = sha256(concat(rawAuthData, bakedHash));
        X509Certificate leaf = makeAppleLeaf(credKp, nonce);

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("x5c"), new CborArray(List.of(new CborBytes(leaf.getEncoded()))));
        CborMap attStmt = new CborMap(m);

        byte[] otherHash = new byte[32];
        otherHash[0] = 1; // baked와 다름
        AttestationException ex = assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, otherHash));
        assertTrue(ex.getMessage().contains("nonce"));
    }

    @Test
    void rejectsMissingNonceExtension() throws Exception {
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        X509Certificate leaf = makeLeafWithoutNonce(credKp);

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("x5c"), new CborArray(List.of(new CborBytes(leaf.getEncoded()))));
        CborMap attStmt = new CborMap(m);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, new byte[32]));
    }

    @Test
    void rejectsCredentialPublicKeyMismatch() throws Exception {
        // cert subject 키 != credential 키 → §8.8 step 5 위반.
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        byte[] nonce = sha256(concat(rawAuthData, clientDataHash));

        // 서로 다른 키로 cert 생성 (nonce는 올바르지만 공개키는 불일치)
        KeyPair otherKp = ecKeyPair();
        X509Certificate leaf = makeAppleLeaf(otherKp, nonce);

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("x5c"), new CborArray(List.of(new CborBytes(leaf.getEncoded()))));
        CborMap attStmt = new CborMap(m);

        AttestationException ex = assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, clientDataHash));
        assertTrue(ex.getMessage().contains("public key"));
    }

    @Test
    void rejectsMissingAttestedCredentialData() throws Exception {
        // AT 플래그 없는 authData (rpIdHash + flags=UP only + signCount), credential data 없음.
        // nonce는 그 authData||clientDataHash로 정확히 계산해 cert에 심어
        // "nonce는 맞지만 credential data 없음" 경로(§8.8 step 5 우회 시도)를 정확히 친다.
        ByteArrayOutputStream ad = new ByteArrayOutputStream();
        ad.writeBytes(new byte[32]);                // rpIdHash
        ad.write(0x01);                             // UP only, AT 없음
        ad.writeBytes(new byte[]{0, 0, 0, 0});      // signCount
        byte[] authDataBytes = ad.toByteArray();

        byte[] clientDataHash = new byte[32];
        byte[] nonce = sha256(concat(authDataBytes, clientDataHash));

        // nonce를 심은 cert (credential key는 아무 EC 키 — AT 없어 pubkey 검사 도달 전 거부)
        X509Certificate leaf = makeAppleLeaf(ecKeyPair(), nonce);

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("x5c"), new CborArray(List.of(new CborBytes(leaf.getEncoded()))));
        CborMap attStmt = new CborMap(m);

        AuthenticatorData parsed = AuthenticatorDataParser.parse(authDataBytes);
        assertNull(parsed.attestedCredentialData()); // AT 없으면 null 파싱 확인
        AttestationException ex = assertThrows(AttestationException.class,
                () -> verifier.verify(parsed, authDataBytes, attStmt, clientDataHash));
        assertTrue(ex.getMessage().contains("attestedCredentialData"));
    }

    @Test
    void rejectsMissingX5c() {
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        CborMap attStmt = new CborMap(m);
        assertThrows(AttestationException.class,
                () -> verifier.verify(null, new byte[37], attStmt, new byte[32]));
    }

    // ---- apple cert 빌더 ----

    /** subject 공개키 = kp, nonce extension(SEQUENCE{[1] EXPLICIT OCTET STRING(nonce)}) 포함 self-signed leaf. */
    private static X509Certificate makeAppleLeaf(KeyPair kp, byte[] nonce) throws Exception {
        X500Name name = new X500Name("CN=apple-leaf");
        var builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(20),
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                name, kp.getPublic());
        // apple nonce extension: SEQUENCE { [1] EXPLICIT OCTET STRING(nonce) }
        builder.addExtension(new ASN1ObjectIdentifier(APPLE_NONCE_OID), false,
                new DERSequence(new DERTaggedObject(true, 1, new DEROctetString(nonce))));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static X509Certificate makeLeafWithoutNonce(KeyPair kp) throws Exception {
        X500Name name = new X500Name("CN=apple-leaf-no-nonce");
        var builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(21),
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                name, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    // ---- helpers (PackedAttestationVerifierTest 패턴 차용) ----

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
