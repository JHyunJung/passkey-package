package com.crosscert.passkey.app.api.v1.rp.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegistrationFinishRequest(
        @NotBlank String registrationToken,
        @NotNull JsonNode publicKeyCredential
) {}
