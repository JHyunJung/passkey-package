package com.crosscert.passkey.app.api.v1.rp.dto;

import tools.jackson.databind.JsonNode;

public record RegistrationStartResponse(
        String registrationToken,
        JsonNode publicKeyCredentialCreationOptions
) {}
