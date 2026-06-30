package com.crosscert.passkey.webauthn.trust;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
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

    @Test
    void expiredCert_passesWhenValidatedAtPastDate_failsAtNow() throws Exception {
        // 루트는 ±365일로 넓게(과거/현재 검증 모두 anchor 유효).
        long day = 86400_000L;
        long now = System.currentTimeMillis();
        TestCa ca = TestCa.createWithValidity(
                new Date(now - 365 * day), new Date(now + 365 * day));
        // leaf 는 30일 전 ~ 10일 전에만 유효(지금은 만료).
        X509Certificate expiredLeaf = ca.issueLeaf(
                "CN=authenticator",
                new Date(now - 30 * day), new Date(now - 10 * day));

        CertPathVerifier verifier = new CertPathVerifier();
        List<X509Certificate> chain = List.of(expiredLeaf);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(ca.root(), null));
        Instant whenValid = Instant.ofEpochMilli(now - 20 * day); // leaf 유효기간 내

        // (a) 유효기간 내 시각으로 3-인자 검증 → true
        assertTrue(verifier.verify(chain, anchors, whenValid),
                "유효기간 내 시각으로 검증하면 만료 leaf도 통과해야 한다");
        // (b) 현재 시각(3-인자 Instant.now()) → false
        assertFalse(verifier.verify(chain, anchors, Instant.now()),
                "현재 시각으로는 만료 leaf 가 거부되어야 한다");
        // (c) 2-인자(= Instant.now() 위임)도 동일하게 false
        assertFalse(verifier.verify(chain, anchors),
                "2-인자 오버로드는 현재 시각 위임이라 만료 leaf 를 거부해야 한다");
    }

    @Test
    void twoArgOverload_delegatesToNow_sameResultAsThreeArgNow() throws Exception {
        TestCa ca = TestCa.create();
        X509Certificate leaf = ca.issueLeaf("CN=authenticator");
        CertPathVerifier verifier = new CertPathVerifier();
        List<X509Certificate> chain = List.of(leaf);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(ca.root(), null));

        // 2-인자(위임)와 3-인자(Instant.now())는 동일 결과여야 한다(현재 시각 기준 정상 체인 → true).
        assertEquals(verifier.verify(chain, anchors, Instant.now()),
                verifier.verify(chain, anchors));
        assertTrue(verifier.verify(chain, anchors));
    }
}
