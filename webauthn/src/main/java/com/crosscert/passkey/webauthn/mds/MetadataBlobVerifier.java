package com.crosscert.passkey.webauthn.mds;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * MDS3 BLOB JWT를 검증·파싱하는 진입점. 네트워크 접근 없음 — rawJwt는
 * 호출자(admin-app)가 다운로드해 넘긴다. 구현체: NativeMetadataBlobVerifier(프로덕션),
 * Webauthn4jMetadataBlobVerifier(differential 테스트 전용).
 */
public interface MetadataBlobVerifier {
    MdsBlob verify(String rawJwt, Set<TrustAnchor> trustAnchors) throws MdsException;
}
