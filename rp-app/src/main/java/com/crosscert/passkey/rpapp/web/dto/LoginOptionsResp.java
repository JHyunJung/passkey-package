package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 인증 시작 응답. {@code POST /passkey/authenticate/begin} 이 돌려준다.
 * publicKeyCredentialRequestOptions 는 브라우저 navigator.credentials.get() 에 넘기고,
 * authenticationToken 은 finish 요청에 다시 실어 보낸다(무상태 릴레이).
 */
public record LoginOptionsResp(
        JsonNode publicKeyCredentialRequestOptions,
        String authenticationToken
) {}
