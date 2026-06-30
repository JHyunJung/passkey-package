package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 인증 완료 요청 본문. {@code POST /passkey/authenticate/finish} 에서 받는다. publicKeyCredential 은 브라우저 인증기 응답, authenticationToken 은 begin 이 돌려준 토큰. */
public record LoginCompleteReq(
        @NotNull JsonNode publicKeyCredential,
        @NotBlank String authenticationToken
) {}
