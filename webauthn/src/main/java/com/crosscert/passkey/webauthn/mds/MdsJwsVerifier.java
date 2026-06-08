package com.crosscert.passkey.webauthn.mds;

import com.crosscert.passkey.webauthn.attestation.JwsEcdsa;
import com.crosscert.passkey.webauthn.trust.CertPathVerifier;

import java.io.ByteArrayInputStream;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MDS3 BLOB의 JWS 서명·X.509 체인 검증 (FIDO MDS3 §3.2).
 *  1) x5c[0]=leaf 공개키로 JWS 서명 검증 (signingInput = header64.payload64).
 *  2) x5c 체인을 trustAnchors(FIDO root)까지 PKIX 검증.
 */
public final class MdsJwsVerifier {

    private final CertPathVerifier certPathVerifier = new CertPathVerifier();

    public void verify(MdsJws jws, Set<TrustAnchor> trustAnchors) throws MdsException {
        if (jws.x5c().isEmpty()) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS has no x5c");
        }
        List<X509Certificate> chain = parseChain(jws.x5c());
        X509Certificate leaf = chain.get(0);

        if (!verifySignature(jws.alg(), leaf, jws.signingInput(), jws.signature())) {
            throw new MdsException(MdsException.Reason.BAD_SIGNATURE, "JWS signature invalid");
        }
        if (!certPathVerifier.verify(chain, trustAnchors)) {
            throw new MdsException(MdsException.Reason.UNTRUSTED_CHAIN, "x5c chain not trusted");
        }
    }

    private static boolean verifySignature(String alg, X509Certificate leaf,
                                           byte[] signingInput, byte[] signature) throws MdsException {
        String jca = switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "ES256" -> "SHA256withECDSA";
            default -> throw new MdsException(MdsException.Reason.MALFORMED_JWS, "unsupported alg: " + alg);
        };
        try {
            Signature s = Signature.getInstance(jca);
            s.initVerify(leaf.getPublicKey());
            s.update(signingInput);
            byte[] toVerify = "ES256".equals(alg) ? JwsEcdsa.toDer(signature) : signature;
            return s.verify(toVerify);
        } catch (Exception e) {
            return false;
        }
    }

    private static List<X509Certificate> parseChain(List<byte[]> x5c) throws MdsException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (byte[] der : x5c) {
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            }
            return chain;
        } catch (Exception e) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "x5c cert parse failed", e);
        }
    }
}
