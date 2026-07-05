package com.crosscert.passkey.webauthn.mds;

import java.security.cert.TrustAnchor;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * 자체 구현 MDS3 BLOB verifier: JWS 파싱 → 서명·체인 검증 → payload 파싱 → 신선도 검증.
 * 네트워크 무의존 — rawJwt는 호출자가 다운로드한다.
 */
public final class NativeMetadataBlobVerifier implements MetadataBlobVerifier {

    private final MdsJwsVerifier jwsVerifier = new MdsJwsVerifier();

    /**
     * 현재 시각 기준 검증 — 기존 호출부 호환을 위한 위임 오버로드.
     */
    @Override
    public MdsBlob verify(String rawJwt, Set<TrustAnchor> trustAnchors) throws MdsException {
        return verify(rawJwt, trustAnchors, Instant.now());
    }

    /**
     * {@code asOf} 기준 시각으로 검증. payload.nextUpdate(MDS3 §3.1.6, 다음 갱신 예정일)가
     * {@code asOf}보다 과거이면 stale blob으로 간주해 거부한다(fail-closed) — 검증 시각을
     * 외부에서 주입해 결정적 검증·테스트 가능성을 확보한다(CertPathVerifier와 동일 패턴).
     */
    public MdsBlob verify(String rawJwt, Set<TrustAnchor> trustAnchors, Instant asOf) throws MdsException {
        MdsJws jws = MdsJws.parse(rawJwt);
        jwsVerifier.verify(jws, trustAnchors);   // 서명·체인 실패 시 throw (파싱 전에 중단)
        MdsBlob blob = MdsBlobParser.parse(jws.payloadBytes());
        if (blob.nextUpdate() != null
                && blob.nextUpdate().atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(asOf)) {
            throw new MdsException(MdsException.Reason.STALE,
                    "MDS blob stale: nextUpdate=" + blob.nextUpdate() + " asOf=" + asOf);
        }
        return blob;
    }
}
