package com.crosscert.passkey.webauthn.mds;

import java.security.cert.TrustAnchor;
import java.time.Instant;
import java.util.Set;

/**
 * MDS3 BLOB JWT를 검증·파싱하는 진입점. 네트워크 접근 없음 — rawJwt는
 * 호출자(admin-app)가 다운로드해 넘긴다. 구현체: NativeMetadataBlobVerifier(프로덕션),
 * Webauthn4jMetadataBlobVerifier(differential 테스트 전용).
 */
public interface MetadataBlobVerifier {
    MdsBlob verify(String rawJwt, Set<TrustAnchor> trustAnchors) throws MdsException;

    /**
     * {@code asOf} 기준 시각으로 신선도(nextUpdate)까지 검증. 기본 구현은 기존 2-인자
     * verify로 위임해 하위호환을 유지한다 — 신선도 검사를 지원하는 구현체
     * (예: NativeMetadataBlobVerifier)는 이 메서드를 override해 실제 asOf를 반영해야
     * 호출부에서 stale blob이 거부된다.
     */
    default MdsBlob verify(String rawJwt, Set<TrustAnchor> trustAnchors, Instant asOf) throws MdsException {
        return verify(rawJwt, trustAnchors);
    }
}
