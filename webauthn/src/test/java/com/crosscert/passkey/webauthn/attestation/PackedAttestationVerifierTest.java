package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackedAttestationVerifierTest {

    private final PackedAttestationVerifier verifier = new PackedAttestationVerifier();

    @Test
    void formatIsPacked() {
        assertEquals("packed", verifier.format());
    }

    @Test
    void acceptsValidSelfAttestation() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair credKp = g.generateKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();

        byte[] x = fixed32(credPub.getW().getAffineX());
        byte[] y = fixed32(credPub.getW().getAffineY());
        byte[] cose = es256Cose(x, y);

        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(credKp.getPrivate());
        sig.update(rawAuthData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(-7));
        m.put(new CborText("sig"), new CborBytes(signature));
        CborMap attStmt = new CborMap(m);

        AttestationResult r = verifier.verify(authData, rawAuthData, attStmt, clientDataHash);
        assertEquals(AttestationResult.Type.SELF, r.type());
    }

    @Test
    void rejectsTamperedSelfAttestation() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair credKp = g.generateKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(-7));
        m.put(new CborText("sig"), new CborBytes(new byte[64]));
        CborMap attStmt = new CborMap(m);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, new byte[32]));
    }

    @Test
    void acceptsValidX5cAttestation() throws Exception {
        // EC 키쌍 + self-signed leaf cert (basicConstraints CA=false, v3)
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair leafKp = g.generateKeyPair();

        java.security.cert.X509Certificate leaf = makeLeafCert(leafKp, /*aaguidExt*/ null);

        // credential COSE는 아무 유효 EC 키 (x5c 경로에선 sig를 leaf 키로 검증하므로 무관)
        java.security.KeyPair credKp = g.generateKeyPair();
        java.security.interfaces.ECPublicKey credPub =
                (java.security.interfaces.ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
        sig.initSign(leafKp.getPrivate());
        sig.update(rawAuthData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(-7));
        m.put(new CborText("sig"), new CborBytes(signature));
        CborArray x5c = new CborArray(java.util.List.of(new CborBytes(leaf.getEncoded())));
        m.put(new CborText("x5c"), x5c);
        CborMap attStmt = new CborMap(m);

        AttestationResult r = verifier.verify(authData, rawAuthData, attStmt, clientDataHash);
        assertEquals(AttestationResult.Type.BASIC, r.type());
    }

    @Test
    void rejectsX5cWithCaLeaf() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair leafKp = g.generateKeyPair();
        java.security.cert.X509Certificate caLeaf = makeCaCert(leafKp); // basicConstraints CA=true

        java.security.KeyPair credKp = g.generateKeyPair();
        java.security.interfaces.ECPublicKey credPub =
                (java.security.interfaces.ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
        sig.initSign(leafKp.getPrivate());
        sig.update(rawAuthData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(-7));
        m.put(new CborText("sig"), new CborBytes(signature));
        m.put(new CborText("x5c"), new CborArray(java.util.List.of(new CborBytes(caLeaf.getEncoded()))));
        CborMap attStmt = new CborMap(m);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, clientDataHash));
    }

    @Test
    void acceptsX5cWithMatchingAaguidExtension() throws Exception {
        // authDataWithCredential은 aaguid를 new byte[16](전부 0)으로 쓰므로
        // leaf cert의 id-fido-gen-ce-aaguid extension도 16바이트 0이면 일치 → 통과해야 함.
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair leafKp = g.generateKeyPair();
        java.security.cert.X509Certificate leaf = makeLeafCert(leafKp, new byte[16]);

        java.security.KeyPair credKp = g.generateKeyPair();
        java.security.interfaces.ECPublicKey credPub =
                (java.security.interfaces.ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
        sig.initSign(leafKp.getPrivate());
        sig.update(rawAuthData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(-7));
        m.put(new CborText("sig"), new CborBytes(signature));
        m.put(new CborText("x5c"), new CborArray(java.util.List.of(new CborBytes(leaf.getEncoded()))));
        CborMap attStmt = new CborMap(m);

        AttestationResult r = verifier.verify(authData, rawAuthData, attStmt, clientDataHash);
        assertEquals(AttestationResult.Type.BASIC, r.type());
    }

    @Test
    void rejectsX5cWithMismatchedAaguidExtension() throws Exception {
        // authData AAGUID는 전부 0인데 cert extension은 다른 값(1로 시작) → 불일치 → 거부.
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair leafKp = g.generateKeyPair();
        byte[] otherAaguid = new byte[16];
        otherAaguid[0] = 1;
        java.security.cert.X509Certificate leaf = makeLeafCert(leafKp, otherAaguid);

        java.security.KeyPair credKp = g.generateKeyPair();
        java.security.interfaces.ECPublicKey credPub =
                (java.security.interfaces.ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        byte[] clientDataHash = new byte[32];
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
        sig.initSign(leafKp.getPrivate());
        sig.update(rawAuthData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(-7));
        m.put(new CborText("sig"), new CborBytes(signature));
        m.put(new CborText("x5c"), new CborArray(java.util.List.of(new CborBytes(leaf.getEncoded()))));
        CborMap attStmt = new CborMap(m);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, clientDataHash));
    }

    private static java.security.cert.X509Certificate makeLeafCert(
            java.security.KeyPair kp, byte[] aaguidExtValue) throws Exception {
        org.bouncycastle.asn1.x500.X500Name name =
                new org.bouncycastle.asn1.x500.X500Name("CN=packed-leaf");
        var builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name, java.math.BigInteger.valueOf(10),
                new java.util.Date(System.currentTimeMillis() - 86400_000L),
                new java.util.Date(System.currentTimeMillis() + 86400_000L),
                name, kp.getPublic());
        // basicConstraints CA=false
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(false));
        if (aaguidExtValue != null) {
            // id-fido-gen-ce-aaguid: OCTET STRING(16바이트 AAGUID)을 extension value로
            builder.addExtension(
                    new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.6.1.4.1.45724.1.1.4"),
                    false,
                    new org.bouncycastle.asn1.DEROctetString(aaguidExtValue));
        }
        org.bouncycastle.operator.ContentSigner signer =
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
                        .build(kp.getPrivate());
        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
    }

    private static java.security.cert.X509Certificate makeCaCert(java.security.KeyPair kp) throws Exception {
        org.bouncycastle.asn1.x500.X500Name name =
                new org.bouncycastle.asn1.x500.X500Name("CN=packed-ca");
        var builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name, java.math.BigInteger.valueOf(11),
                new java.util.Date(System.currentTimeMillis() - 86400_000L),
                new java.util.Date(System.currentTimeMillis() + 86400_000L),
                name, kp.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(true)); // CA=true
        org.bouncycastle.operator.ContentSigner signer =
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
                        .build(kp.getPrivate());
        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
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

    private static byte[] fixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
