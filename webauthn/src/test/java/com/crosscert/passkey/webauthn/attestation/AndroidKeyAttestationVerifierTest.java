package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fmt=android-key (WebAuthn §8.4) 검증기 테스트.
 * android 에뮬레이터 없이 BouncyCastle로 KeyDescription extension
 * (OID 1.3.6.1.4.1.11129.2.1.17)을 담은 leaf cert를 직접 만든다.
 *
 * 핵심 성질:
 *  - attStmt.sig 가 authData||clientDataHash 에 대한 leaf 공개키 유효 서명
 *  - credential 공개키 == leaf(credCert) subject 공개키
 *  - KeyDescription.attestationChallenge == clientDataHash
 */
class AndroidKeyAttestationVerifierTest {

    private static final String KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17";

    private final AndroidKeyAttestationVerifier verifier = new AndroidKeyAttestationVerifier();

    @Test
    void formatIsAndroidKey() {
        assertEquals("android-key", verifier.format());
    }

    @Test
    void acceptsValidAndroidKeyAttestation() throws Exception {
        // 1) credential 키쌍 = cert subject 키 (§8.4 step 2)
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));

        // 2) authData (AT 플래그 + credential COSE)
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        // 3) clientDataHash (임의)
        byte[] clientDataHash = new byte[32];
        for (int i = 0; i < 32; i++) clientDataHash[i] = (byte) (i + 1);

        // 4) leaf cert: subject = credential 키, KeyDescription.attestationChallenge = clientDataHash
        X509Certificate leaf = makeAndroidKeyLeaf(credKp, clientDataHash);

        // 5) attStmt.sig = credential 개인키로 authData||clientDataHash 서명
        byte[] signature = signAttStmt(credKp, rawAuthData, clientDataHash);

        CborMap attStmt = attStmt(-7, signature, leaf);

        AttestationResult r = verifier.verify(authData, rawAuthData, attStmt, clientDataHash);
        assertEquals(AttestationResult.Type.BASIC, r.type());
        assertEquals(1, r.trustPath().size());
    }

    @Test
    void rejectsWrongAttestationChallenge() throws Exception {
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        for (int i = 0; i < 32; i++) clientDataHash[i] = (byte) (i + 1);

        // cert에는 다른 challenge를 굽는다 → §8.4 challenge 검증 실패
        byte[] wrongChallenge = new byte[32];
        for (int i = 0; i < 32; i++) wrongChallenge[i] = (byte) 0xAB;
        X509Certificate leaf = makeAndroidKeyLeaf(credKp, wrongChallenge);

        byte[] signature = signAttStmt(credKp, rawAuthData, clientDataHash);
        CborMap attStmt = attStmt(-7, signature, leaf);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, clientDataHash));
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        for (int i = 0; i < 32; i++) clientDataHash[i] = (byte) (i + 1);

        X509Certificate leaf = makeAndroidKeyLeaf(credKp, clientDataHash);

        byte[] signature = signAttStmt(credKp, rawAuthData, clientDataHash);
        signature[signature.length - 1] ^= 0x01; // 마지막 바이트 플립 → 서명 무효
        CborMap attStmt = attStmt(-7, signature, leaf);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, clientDataHash));
    }

    @Test
    void rejectsMissingExtension() throws Exception {
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        for (int i = 0; i < 32; i++) clientDataHash[i] = (byte) (i + 1);

        // KeyDescription extension 없는 leaf
        X509Certificate leaf = makeLeafWithoutExtension(credKp);

        byte[] signature = signAttStmt(credKp, rawAuthData, clientDataHash);
        CborMap attStmt = attStmt(-7, signature, leaf);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, clientDataHash));
    }

    @Test
    void rejectsMalformedKeyDescriptionTooFewElements() throws Exception {
        // sig/pubkey는 유효하게 두고, KeyDescription에 요소가 2개뿐(5번째 challenge에 못 감)
        // → 엄격 파서가 AttestationException. "첫 OCTET STRING" 휴리스틱 회귀 방지.
        KeyPair credKp = ecKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        for (int i = 0; i < 32; i++) clientDataHash[i] = (byte) (i + 1);

        org.bouncycastle.asn1.ASN1Sequence shortSeq = new org.bouncycastle.asn1.DERSequence(
                new org.bouncycastle.asn1.ASN1Encodable[]{
                        new org.bouncycastle.asn1.ASN1Integer(3),
                        new org.bouncycastle.asn1.ASN1Enumerated(1)
                });
        X509Certificate leaf = makeLeafWithKeyDescription(credKp, shortSeq);

        byte[] signature = signAttStmt(credKp, rawAuthData, clientDataHash);
        CborMap attStmt = attStmt(-7, signature, leaf);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, clientDataHash));
    }

    // ---- android-key cert 빌더 ----

    /**
     * subject 공개키 = kp, KeyDescription extension(OID 1.3.6.1.4.1.11129.2.1.17) 포함 self-signed leaf.
     * 실제 Android Keystore Attestation의 8요소 구조(§8.4):
     *  { INTEGER(attestationVersion), ENUMERATED(attestationSecurityLevel),
     *    INTEGER(keymasterVersion), ENUMERATED(keymasterSecurityLevel),
     *    OCTET STRING(attestationChallenge) ← 5번째, OCTET STRING(uniqueId),
     *    SEQUENCE(softwareEnforced), SEQUENCE(teeEnforced) }
     */
    private static X509Certificate makeAndroidKeyLeaf(KeyPair kp, byte[] challenge) throws Exception {
        org.bouncycastle.asn1.ASN1Encodable[] kd = new org.bouncycastle.asn1.ASN1Encodable[]{
                new org.bouncycastle.asn1.ASN1Integer(3),                 // attestationVersion
                new org.bouncycastle.asn1.ASN1Enumerated(1),              // attestationSecurityLevel (TEE)
                new org.bouncycastle.asn1.ASN1Integer(4),                 // keymasterVersion
                new org.bouncycastle.asn1.ASN1Enumerated(1),              // keymasterSecurityLevel
                new org.bouncycastle.asn1.DEROctetString(challenge),      // attestationChallenge ← 5번째
                new org.bouncycastle.asn1.DEROctetString(new byte[0]),    // uniqueId
                new org.bouncycastle.asn1.DERSequence(),                  // softwareEnforced (empty)
                new org.bouncycastle.asn1.DERSequence()                   // teeEnforced (empty)
        };
        return makeLeafWithKeyDescription(kp, new org.bouncycastle.asn1.DERSequence(kd));
    }

    /**
     * 임의의 KeyDescription SEQUENCE를 extension에 심은 leaf. 실제 Android attestation처럼
     * extnValue(DER)가 SEQUENCE 자체이고, getExtensionValue가 OCTET STRING 한 겹을 추가한다
     * (추가 OCTET STRING 래핑 금지).
     */
    private static X509Certificate makeLeafWithKeyDescription(
            KeyPair kp, org.bouncycastle.asn1.ASN1Sequence keyDescription) throws Exception {
        return buildLeaf(kp, keyDescription);
    }

    private static X509Certificate makeLeafWithoutExtension(KeyPair kp) throws Exception {
        return buildLeaf(kp, null);
    }

    private static X509Certificate buildLeaf(KeyPair kp, org.bouncycastle.asn1.ASN1Encodable extValue)
            throws Exception {
        org.bouncycastle.asn1.x500.X500Name name =
                new org.bouncycastle.asn1.x500.X500Name("CN=android-key-leaf");
        var builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(30),
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                name, kp.getPublic());
        if (extValue != null) {
            builder.addExtension(
                    new org.bouncycastle.asn1.ASN1ObjectIdentifier(KEY_DESCRIPTION_OID), false, extValue);
        }
        org.bouncycastle.operator.ContentSigner signer =
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
                        .build(kp.getPrivate());
        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
    }

    // ---- helpers (PackedAttestationVerifierTest 패턴 차용) ----

    private static byte[] signAttStmt(KeyPair kp, byte[] rawAuthData, byte[] clientDataHash) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(rawAuthData);
        sig.update(clientDataHash);
        return sig.sign();
    }

    private static CborMap attStmt(long alg, byte[] sig, X509Certificate leaf) throws Exception {
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(alg));
        m.put(new CborText("sig"), new CborBytes(sig));
        m.put(new CborText("x5c"), new CborArray(List.of(new CborBytes(leaf.getEncoded()))));
        return new CborMap(m);
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
}
