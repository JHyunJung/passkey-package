package com.crosscert.passkey.webauthn.trust;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * attestation 체인 검증의 루트 신뢰 앵커 공급자. 본래 MDS에서 오지만
 * 이번 범위에서는 인터페이스만 두고, MDS 서브프로젝트가 구현해 끼운다.
 *
 * @param aaguid 등록 authenticator의 AAGUID (16바이트, nullable)
 * @param attestationFormat "packed", "tpm" 등
 */
public interface TrustAnchorProvider {
    Set<TrustAnchor> trustAnchors(byte[] aaguid, String attestationFormat);
}
