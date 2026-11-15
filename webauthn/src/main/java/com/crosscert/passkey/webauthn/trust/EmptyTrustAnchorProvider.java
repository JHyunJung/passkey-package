package com.crosscert.passkey.webauthn.trust;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * 신뢰 앵커 없음 — TRUST_CHAIN_REQUIRED 에서는 none/self 가 먼저 거부되고 나머지도 체인 검증이
 * 실패하므로 모든 등록이 ATTESTATION_UNTRUSTED 가 된다. 다른 정책에서는 조회되지 않는다.
 */
public final class EmptyTrustAnchorProvider implements TrustAnchorProvider {
    @Override
    public Set<TrustAnchor> trustAnchors(byte[] aaguid, String attestationFormat) {
        return Set.of();
    }
}
