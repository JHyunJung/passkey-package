package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationStartResponse(
        String authenticationToken,
        JsonNode publicKeyCredentialRequestOptions
) {}
