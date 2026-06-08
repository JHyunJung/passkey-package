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
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fmt=tpm (WebAuthn §8.3, TPM 2.0) 검증 테스트.
 *
 * <p>충실한 RSA credential + EC AIK 조합의 TPM attestation을 바이트 단위로 직접
 * 조립한다. pubArea(TPMT_PUBLIC)·certInfo(TPMS_ATTEST)의 정확한 바이트 레이아웃이
 * 이 테스트의 핵심 — 검증기 파서는 이 레이아웃에 맞춰야 한다.
 */
class TpmAttestationVerifierTest {

    private final TpmAttestationVerifier verifier = new TpmAttestationVerifier();

    // TPM 상수
    private static final long TPM_GENERATED_VALUE = 0xFF544347L;
    private static final int TPM_ST_ATTEST_CERTIFY = 0x8017;
    private static final int TPM_ALG_RSA = 0x0001;
    private static final int TPM_ALG_SHA256 = 0x000B;
    private static final int TPM_ALG_NULL = 0x0010;
    private static final String EKU_AIK = "2.23.133.8.3";

    @Test
    void formatIsTpm() {
        assertEquals("tpm", verifier.format());
    }

    @Test
    void acceptsValidTpmAttestation() throws Exception {
        Fixture f = new Fixture().build();
        AttestationResult r = verifier.verify(f.authData, f.rawAuthData, f.attStmt, f.clientDataHash);
        assertEquals(AttestationResult.Type.ATT_CA, r.type());
        assertFalse(r.trustPath().isEmpty());
    }

    @Test
    void rejectsBadMagic() throws Exception {
        Fixture f = new Fixture();
        f.magic = 0xDEADBEEFL; // != TPM_GENERATED_VALUE
        f.build();
        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, f.attStmt, f.clientDataHash));
    }

    @Test
    void rejectsWrongExtraData() throws Exception {
        // extraData = SHA-256(attToBeSigned)인데, verify에 다른 clientDataHash를 넘기면
        // 재계산한 해시가 certInfo.extraData와 어긋나 거부되어야 한다.
        Fixture f = new Fixture().build();
        byte[] wrongClientDataHash = new byte[32];
        wrongClientDataHash[0] = 0x42;
        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, f.attStmt, wrongClientDataHash));
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        Fixture f = new Fixture();
        f.tamperSignature = true;
        f.build();
        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, f.attStmt, f.clientDataHash));
    }

    @Test
    void rejectsPubKeyMismatch() throws Exception {
        // pubArea의 modulus를 credential 키와 다른 값으로 바꾼다 → 불일치 → 거부.
        Fixture f = new Fixture();
        f.corruptPubAreaModulus = true;
        f.build();
        assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, f.attStmt, f.clientDataHash));
    }

    @Test
    void rejectsCertInfoTrailingBytes() throws Exception {
        // 유효 certInfo 뒤에 잉여 바이트가 붙으면 완전소비 검사에서 거부되어야 한다 (codex P2).
        Fixture f = new Fixture();
        f.appendCertInfoTrailingByte = true;
        f.build();
        AttestationException ex = assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, f.attStmt, f.clientDataHash));
        assertTrue(ex.getMessage().contains("trailing"), "expected trailing-byte rejection, got: " + ex.getMessage());
    }

    @Test
    void rejectsAikWithoutBasicConstraints() throws Exception {
        // basicConstraints extension이 없는 AIK cert는 거부되어야 한다 (codex P2).
        Fixture f = new Fixture();
        f.omitBasicConstraints = true;
        f.build();
        AttestationException ex = assertThrows(AttestationException.class,
                () -> verifier.verify(f.authData, f.rawAuthData, f.attStmt, f.clientDataHash));
        assertTrue(ex.getMessage().contains("basicConstraints"), "expected BC-missing rejection, got: " + ex.getMessage());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture: RSA credential + EC AIK TPM attestation을 바이트 단위로 조립.
    // ─────────────────────────────────────────────────────────────────────────
    private static final class Fixture {
        long magic = TPM_GENERATED_VALUE;
        boolean tamperSignature = false;
        boolean corruptPubAreaModulus = false;
        boolean appendCertInfoTrailingByte = false;
        boolean omitBasicConstraints = false;

        AuthenticatorData authData;
        byte[] rawAuthData;
        byte[] clientDataHash;
        CborMap attStmt;

        Fixture build() throws Exception {
            // 1. credential = RSA 2048 키쌍 (기본 exponent 65537)
            KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
            rsaGen.initialize(2048);
            KeyPair credKp = rsaGen.generateKeyPair();
            RSAPublicKey credPub = (RSAPublicKey) credKp.getPublic();
            assertEquals(BigInteger.valueOf(65537), credPub.getPublicExponent());

            byte[] modulus = unsignedBytes(credPub.getModulus());           // 256바이트
            byte[] exponent = unsignedBytes(credPub.getPublicExponent());   // 0x010001
            byte[] cose = rs256Cose(modulus, exponent);

            rawAuthData = authDataWithCredential(cose);
            authData = AuthenticatorDataParser.parse(rawAuthData);

            clientDataHash = new byte[32]; // happy path: 전부 0

            // 2. pubArea (TPMT_PUBLIC, RSA)
            byte[] pubAreaModulus = modulus;
            if (corruptPubAreaModulus) {
                pubAreaModulus = modulus.clone();
                pubAreaModulus[0] ^= 0x01; // modulus 1비트 변경
            }
            byte[] pubArea = buildRsaPubArea(pubAreaModulus);

            // 3. attToBeSigned = authData || clientDataHash → extraData = SHA-256
            byte[] attToBeSigned = concat(rawAuthData, clientDataHash);
            byte[] extraData = sha256(attToBeSigned);

            // 4. attestedName = nameAlg(2바이트 BE) || SHA-256(pubArea)
            byte[] attestedName = concat(u16(TPM_ALG_SHA256), sha256(pubArea));

            // 5. certInfo (TPMS_ATTEST)
            byte[] certInfo = buildCertInfo(magic, extraData, attestedName);
            if (appendCertInfoTrailingByte) {
                // 유효 certInfo 뒤에 잉여 바이트 1개 → 완전소비 검사에서 거부되어야 함.
                certInfo = concat(certInfo, new byte[]{0x00});
            }

            // 6. AIK = EC 키쌍 + leaf cert (CA=false, EKU 2.23.133.8.3)
            KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
            ecGen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair aikKp = ecGen.generateKeyPair();
            java.security.cert.X509Certificate aikCert = makeAikCert(aikKp, !omitBasicConstraints);

            // 서명은 (잉여 바이트 포함) certInfo 전체에 대해 — 그래야 trailing 검사가 sig 검사보다
            // 먼저 실패함을 분리 검증할 수 있다.
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(aikKp.getPrivate());
            signer.update(certInfo);
            byte[] signature = signer.sign();
            if (tamperSignature) {
                signature[signature.length - 1] ^= 0x01;
            }

            // 7. attStmt CBOR map
            Map<CborValue, CborValue> m = new LinkedHashMap<>();
            m.put(new CborText("ver"), new CborText("2.0"));
            m.put(new CborText("alg"), new CborInt(-7)); // EC AIK → ES256
            m.put(new CborText("sig"), new CborBytes(signature));
            m.put(new CborText("x5c"), new CborArray(List.of(new CborBytes(aikCert.getEncoded()))));
            m.put(new CborText("certInfo"), new CborBytes(certInfo));
            m.put(new CborText("pubArea"), new CborBytes(pubArea));
            attStmt = new CborMap(m);
            return this;
        }
    }

    /** TPMT_PUBLIC (RSA): type|nameAlg|objectAttributes|authPolicy|params|unique. */
    private static byte[] buildRsaPubArea(byte[] modulus) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(u16(TPM_ALG_RSA));       // type
        o.writeBytes(u16(TPM_ALG_SHA256));    // nameAlg
        o.writeBytes(u32(0x00050072));        // objectAttributes (임의)
        o.writeBytes(tpm2b(new byte[0]));     // authPolicy (빈 TPM2B → 00 00)
        // TPMS_RSA_PARMS
        o.writeBytes(u16(TPM_ALG_NULL));      // symmetric = TPM_ALG_NULL
        o.writeBytes(u16(TPM_ALG_NULL));      // scheme = TPM_ALG_NULL
        o.writeBytes(u16(2048));              // keyBits
        o.writeBytes(u32(0));                 // exponent (0 = default 65537)
        // unique = TPM2B_PUBLIC_KEY_RSA
        o.writeBytes(tpm2b(modulus));
        return o.toByteArray();
    }

    /** TPMS_ATTEST (type=CERTIFY). */
    private static byte[] buildCertInfo(long magic, byte[] extraData, byte[] attestedName) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(u32(magic));                       // magic
        o.writeBytes(u16(TPM_ST_ATTEST_CERTIFY));       // type
        o.writeBytes(tpm2b(new byte[0]));               // qualifiedSigner (빈 TPM2B_NAME)
        o.writeBytes(tpm2b(extraData));                 // extraData (TPM2B_DATA)
        o.writeBytes(new byte[17]);                     // clockInfo (8+4+4+1)
        o.writeBytes(new byte[8]);                      // firmwareVersion (UINT64)
        // attested (TPMS_CERTIFY_INFO): name(TPM2B) || qualifiedName(TPM2B)
        o.writeBytes(tpm2b(attestedName));              // name
        o.writeBytes(tpm2b(new byte[0]));               // qualifiedName (빈 TPM2B)
        return o.toByteArray();
    }

    private static java.security.cert.X509Certificate makeAikCert(KeyPair kp, boolean withBasicConstraints)
            throws Exception {
        // 실제 TPM AIK는 subject가 비어있고 SAN(manufacturer 등)을 제공한다. 검증기는 subject
        // emptiness/SAN을 검사하지 않으므로(문서화된 한계), fixture는 cert가 파싱 가능하도록
        // 최소 CN을 둔다.
        org.bouncycastle.asn1.x500.X500Name subject = new org.bouncycastle.asn1.x500.X500Name("CN=tpm-aik");
        org.bouncycastle.asn1.x500.X500Name issuer = new org.bouncycastle.asn1.x500.X500Name("CN=tpm-aik-ca");
        var builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(20),
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                subject, kp.getPublic());
        // basicConstraints CA=false (negative 테스트에선 생략하여 BC extension 부재를 만든다)
        if (withBasicConstraints) {
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                    new org.bouncycastle.asn1.x509.BasicConstraints(false));
        }
        // EKU id-tcg-kp-AIKCertificate (2.23.133.8.3)
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, false,
                new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                        org.bouncycastle.asn1.x509.KeyPurposeId.getInstance(
                                new org.bouncycastle.asn1.ASN1ObjectIdentifier(EKU_AIK))));
        org.bouncycastle.operator.ContentSigner signer =
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
                        .build(kp.getPrivate());
        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
    }

    // ── COSE / authData 헬퍼 ────────────────────────────────────────────────

    /** RS256 COSE_Key: kty=3(RSA), alg=-257, n(-1), e(-2). */
    private static byte[] rs256Cose(byte[] n, byte[] e) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa4);                       // map(4)
        o.write(0x01); o.write(0x03);        // kty=3 (RSA)
        o.write(0x03); o.write(0x39); o.write(0x01); o.write(0x00); // alg=-257 (negint, 0x0100=256)
        o.write(0x20);                       // key -1 (n)
        writeCborByteString(o, n);
        o.write(0x21);                       // key -2 (e)
        writeCborByteString(o, e);
        return o.toByteArray();
    }

    private static void writeCborByteString(ByteArrayOutputStream o, byte[] b) {
        if (b.length < 24) {
            o.write(0x40 | b.length);
        } else if (b.length < 256) {
            o.write(0x58); o.write(b.length);
        } else if (b.length < 65536) {
            o.write(0x59); o.write((b.length >> 8) & 0xff); o.write(b.length & 0xff);
        } else {
            throw new IllegalArgumentException("byte string too long");
        }
        o.writeBytes(b);
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

    // ── 바이트 유틸 ─────────────────────────────────────────────────────────

    private static byte[] u16(int v) {
        return new byte[]{(byte) ((v >> 8) & 0xff), (byte) (v & 0xff)};
    }

    private static byte[] u32(long v) {
        return new byte[]{
                (byte) ((v >> 24) & 0xff), (byte) ((v >> 16) & 0xff),
                (byte) ((v >> 8) & 0xff), (byte) (v & 0xff)};
    }

    /** TPM2B: UINT16 size prefix + bytes. */
    private static byte[] tpm2b(byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(u16(body.length));
        o.writeBytes(body);
        return o.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    /** BigInteger를 부호 없는 big-endian 바이트로 (선행 0x00 제거). */
    private static byte[] unsignedBytes(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length > 1 && raw[0] == 0x00) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return raw;
    }
}
