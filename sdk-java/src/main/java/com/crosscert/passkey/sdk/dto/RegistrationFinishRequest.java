package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RegistrationFinishRequest(
        String registrationToken,
        JsonNode publicKeyCredential
) {}
