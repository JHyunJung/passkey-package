package com.crosscert.passkey.webauthn.mds;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * 자체 구현 MDS3 BLOB verifier: JWS 파싱 → 서명·체인 검증 → payload 파싱.
 * 네트워크 무의존 — rawJwt는 호출자가 다운로드한다.
 */
public final class NativeMetadataBlobVerifier implements MetadataBlobVerifier {

    private final MdsJwsVerifier jwsVerifier = new MdsJwsVerifier();

    @Override
    public MdsBlob verify(String rawJwt, Set<TrustAnchor> trustAnchors) throws MdsException {
        MdsJws jws = MdsJws.parse(rawJwt);
        jwsVerifier.verify(jws, trustAnchors);   // 서명·체인 실패 시 throw (파싱 전에 중단)
        return MdsBlobParser.parse(jws.payloadBytes());
    }
}
