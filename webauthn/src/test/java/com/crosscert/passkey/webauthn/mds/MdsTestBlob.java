package com.crosscert.passkey.webauthn.mds;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

/**
 * 테스트용 MDS3 BLOB JWS 빌더. self-signed root CA로 leaf를 발급하고
 * leaf 개인키로 RS256 JWS를 서명한다. (네트워크·실 FIDO PKI 불필요.)
 */
public final class MdsTestBlob {

    public final X509Certificate root;
    public final X509Certificate leaf;
    public final String jws;

    private MdsTestBlob(X509Certificate root, X509Certificate leaf, String jws) {
        this.root = root; this.leaf = leaf; this.jws = jws;
    }

    public static MdsTestBlob rs256(String payloadJson) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair rootKp = g.generateKeyPair();
        KeyPair leafKp = g.generateKeyPair();

        X509Certificate root = build(rootKp, "CN=mds-test-root", rootKp, "CN=mds-test-root", true);
        X509Certificate leaf = build(rootKp, "CN=mds-test-root", leafKp, "CN=mds-signer", false);

        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"RS256\",\"x5c\":[\""
                + Base64.getEncoder().encodeToString(leaf.getEncoded()) + "\",\""
                + Base64.getEncoder().encodeToString(root.getEncoded()) + "\"]}";
        String h64 = b64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String p64 = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(leafKp.getPrivate());
        s.update((h64 + "." + p64).getBytes(StandardCharsets.US_ASCII));
        String sig = b64.encodeToString(s.sign());
        return new MdsTestBlob(root, leaf, h64 + "." + p64 + "." + sig);
    }

    private static X509Certificate build(KeyPair issuerKp, String issuerDn,
                                         KeyPair subjectKp, String subjectDn, boolean ca) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuerDn), BigInteger.valueOf(ca ? 1 : 2),
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                new X500Name(subjectDn), subjectKp.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(ca));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
