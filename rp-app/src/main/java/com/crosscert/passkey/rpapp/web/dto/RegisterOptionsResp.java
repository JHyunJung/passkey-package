package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * register/begin 응답. 무상태 릴레이를 위해 registrationToken·userHandle 을 HMAC 서명한
 * 불투명 regRelayToken(spec §5)을 반환한다. 클라이언트는 register/finish 에 이 토큰을
 * 다시 실어 보낸다(userHandle 조작 불가).
 */
public record RegisterOptionsResp(
        JsonNode publicKeyCredentialCreationOptions,
        String regRelayToken) {}
