package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 등록 시작 응답. {@code POST /passkey/register/begin} 이 돌려준다.
 * publicKeyCredentialCreationOptions 는 브라우저 navigator.credentials.create() 에 그대로 넘기고,
 * regRelayToken 은 finish 요청에 다시 실어 보낸다(서버 세션 없이 begin↔finish 를 잇는 서명 토큰).
 */
public record RegisterOptionsResp(
        JsonNode publicKeyCredentialCreationOptions,
        String regRelayToken
) {}
