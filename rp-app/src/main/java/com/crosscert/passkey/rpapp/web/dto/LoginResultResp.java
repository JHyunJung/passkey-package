package com.crosscert.passkey.rpapp.web.dto;

/**
 * authenticate/finish 성공 결과. id-token 은 rp-app 내부에서만 검증·소비하고 노출하지
 * 않는다(spec §4). 클라이언트는 이 결과로 "인증됨"을 알고 자기 UX 를 진행한다.
 */
public record LoginResultResp(
        boolean authenticated,
        String userHandle,
        String displayName
) {}
