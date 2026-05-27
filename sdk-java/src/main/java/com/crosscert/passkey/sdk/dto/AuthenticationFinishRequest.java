package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AuthenticationFinishRequest(
        String authenticationToken,
        JsonNode publicKeyCredential
) {}
