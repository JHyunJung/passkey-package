package com.crosscert.passkey.webauthn.trust;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;

/** 테스트용 미니 CA — self-signed 루트와 그 루트가 서명한 leaf를 발급. */
final class TestCa {

    private final KeyPair rootKeyPair;
    private final X509Certificate root;

    private TestCa(KeyPair rootKeyPair, X509Certificate root) {
        this.rootKeyPair = rootKeyPair;
        this.root = root;
    }

    static TestCa create() throws Exception {
        return createWithValidity(
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L));
    }

    /** 검증 시각 주입 테스트용 — 루트 유효기간을 넓게 잡아 과거/현재 양쪽 검증에 anchor가 유효하도록 한다. */
    static TestCa createWithValidity(Date rootNotBefore, Date rootNotAfter) throws Exception {
        KeyPair kp = ecKeyPair();
        X509Certificate root = selfSign(kp, "CN=test-root", rootNotBefore, rootNotAfter);
        return new TestCa(kp, root);
    }

    X509Certificate root() { return root; }

    X509Certificate issueLeaf(String subjectDn) throws Exception {
        return issueLeaf(subjectDn,
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L));
    }

    /** notBefore/notAfter를 명시해 leaf를 발급(만료된 leaf 등 시각 의존 케이스 테스트용). */
    X509Certificate issueLeaf(String subjectDn, Date notBefore, Date notAfter) throws Exception {
        KeyPair leafKp = ecKeyPair();
        X500Name issuer = new X500Name("CN=test-root");
        X500Name subject = new X500Name(subjectDn);
        var builder = new JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(2),
                notBefore, notAfter,
                subject, leafKp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(rootKeyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static X509Certificate selfSign(KeyPair kp, String dn, Date notBefore, Date notAfter) throws Exception {
        X500Name name = new X500Name(dn);
        var builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE,
                notBefore, notAfter,
                name, kp.getPublic());
        builder.addExtension(
                org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(true));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }
}
