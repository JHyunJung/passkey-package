package com.crosscert.passkey.webauthn.trust;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * attestation 체인 검증(TRUST_CHAIN_REQUIRED)의 루트 신뢰 앵커 공급자.
 * 구현은 EmptyTrustAnchorProvider 뿐이다 — passkey-app 은 withDefaults 로 구성하고
 * 등록 때 SELF_ALLOWED 를 넘기므로, attestation 체인을 루트까지 검증하는 경로는 쓰이지 않는다.
 * passkey-app 의 MdsVerifier 도 이를 대신하지 않는다 — 인증기가 보고한 AAGUID 의 MDS 상태만
 * 조회하고 인증서 체인은 검증하지 않는다.
 *
 * @param aaguid 등록 authenticator의 AAGUID (16바이트, nullable)
 * @param attestationFormat "packed", "tpm" 등
 */
public interface TrustAnchorProvider {
    Set<TrustAnchor> trustAnchors(byte[] aaguid, String attestationFormat);
}
