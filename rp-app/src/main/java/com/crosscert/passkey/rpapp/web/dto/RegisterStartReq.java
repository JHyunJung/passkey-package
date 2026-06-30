package com.crosscert.passkey.rpapp.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 등록 시작 요청 본문. {@code POST /passkey/register/begin} 에서 받는다. */
public record RegisterStartReq(
        @NotBlank String username,
        @NotBlank String displayName
) {}
