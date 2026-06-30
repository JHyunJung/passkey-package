package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 등록 완료 요청 본문. {@code POST /passkey/register/finish} 에서 받는다. publicKeyCredential 은 브라우저가 만든 인증기 응답, regRelayToken 은 begin 이 돌려준 서명 토큰. */
public record RegisterCompleteReq(
        @NotNull JsonNode publicKeyCredential,
        @NotBlank String regRelayToken
) {}
