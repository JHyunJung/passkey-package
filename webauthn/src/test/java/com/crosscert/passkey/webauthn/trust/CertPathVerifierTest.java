package com.crosscert.passkey.webauthn.trust;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CertPathVerifierTest {

    @Test
    void acceptsChainToTrustedRoot() throws Exception {
        TestCa ca = TestCa.create();
        X509Certificate leaf = ca.issueLeaf("CN=authenticator");

        CertPathVerifier verifier = new CertPathVerifier();
        boolean ok = verifier.verify(
                List.of(leaf),
                Set.of(new TrustAnchor(ca.root(), null)));

        assertTrue(ok);
    }

    @Test
    void rejectsChainWithUntrustedRoot() throws Exception {
        TestCa ca = TestCa.create();
        TestCa otherCa = TestCa.create();
        X509Certificate leaf = ca.issueLeaf("CN=authenticator");

        CertPathVerifier verifier = new CertPathVerifier();
        boolean ok = verifier.verify(
                List.of(leaf),
                Set.of(new TrustAnchor(otherCa.root(), null)));  // 잘못된 루트

        assertFalse(ok);
    }

    @Test
    void rejectsEmptyAnchors() {
        assertFalse(new CertPathVerifier().verify(List.of(), Set.of()));
    }

    @Test
    void rejectsNullChain() {
        assertFalse(new CertPathVerifier().verify(null, Set.of()));
    }
}
