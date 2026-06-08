package com.crosscert.passkey.webauthn.trust;

import java.security.cert.TrustAnchor;
import java.util.Set;

/** 신뢰 앵커 없음 — TRUST_CHAIN_REQUIRED를 만족시키지 못하므로 self/none만 통과. */
public final class EmptyTrustAnchorProvider implements TrustAnchorProvider {
    @Override
    public Set<TrustAnchor> trustAnchors(byte[] aaguid, String attestationFormat) {
        return Set.of();
    }
}
