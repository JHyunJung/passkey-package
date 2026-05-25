package com.crosscert.passkey.app.api.v1.rp.dto;

import jakarta.validation.constraints.NotBlank;

public record RegistrationStartRequest(
        @NotBlank String userHandle,    // base64url
        @NotBlank String displayName,
        @NotBlank String username
) {}
