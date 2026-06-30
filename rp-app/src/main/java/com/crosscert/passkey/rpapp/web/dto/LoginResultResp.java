package com.crosscert.passkey.rpapp.web.dto;

/**
 * 인증 완료 결과. {@code POST /passkey/authenticate/finish} 가 돌려준다.
 * id-token 은 rp-app 내부에서만 검증·소비하고 외부에 노출하지 않는다.
 * 클라이언트는 이 결과로 "인증됨" 을 확인하고 자기 화면 흐름을 진행한다.
 */
public record LoginResultResp(
        boolean authenticated,
        String userHandle,
        String displayName
) {}
