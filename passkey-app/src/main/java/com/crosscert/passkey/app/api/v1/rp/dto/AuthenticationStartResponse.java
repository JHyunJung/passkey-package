package com.crosscert.passkey.app.api.v1.rp.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AuthenticationStartResponse(
        String authenticationToken,
        JsonNode publicKeyCredentialRequestOptions
) {}
