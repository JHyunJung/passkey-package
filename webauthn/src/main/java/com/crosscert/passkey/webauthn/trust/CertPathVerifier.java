package com.crosscert.passkey.webauthn.trust;

import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

/**
 * X.509 인증서 체인 검증 (leaf → 루트). JDK CertPathValidator(PKIX) 래핑.
 * 폐기(CRL/OCSP) 검사는 비활성 — attestation 인증서는 보통 폐기 정보가 없고,
 * 디바이스 신뢰는 MDS status report로 별도 관리하기 때문이다.
 */
public final class CertPathVerifier {

    /** chain[0]=leaf 순서. anchors가 비면 항상 false. 검증 실패 시 false(예외 흡수). */
    public boolean verify(List<X509Certificate> chain, Set<TrustAnchor> anchors) {
        if (anchors == null || anchors.isEmpty() || chain == null || chain.isEmpty()) {
            return false;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            CertPath path = cf.generateCertPath(chain);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(path, params);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
