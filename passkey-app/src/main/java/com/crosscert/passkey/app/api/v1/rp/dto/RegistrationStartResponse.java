package com.crosscert.passkey.app.api.v1.rp.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RegistrationStartResponse(
        String registrationToken,
        JsonNode publicKeyCredentialCreationOptions
) {}
