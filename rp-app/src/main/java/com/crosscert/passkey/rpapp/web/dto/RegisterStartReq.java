package com.crosscert.passkey.rpapp.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterStartReq(
        @NotBlank String username,
        @NotBlank String displayName
) {}
