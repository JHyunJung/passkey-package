package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * register/begin 응답. 무상태 릴레이를 위해 passkey-app 의 단명 registrationToken 과
 * userHandle 을 클라이언트에 함께 반환한다. 클라이언트는 register/finish 요청에 이 둘을
 * 다시 실어 보낸다. (토큰은 256bit·5분 TTL·1회성이라 노출돼도 개인키 서명 없이는 무력.)
 */
public record RegisterOptionsResp(
        JsonNode publicKeyCredentialCreationOptions,
        String registrationToken,
        String userHandle) {}
