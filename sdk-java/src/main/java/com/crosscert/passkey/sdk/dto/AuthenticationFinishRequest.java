package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record AuthenticationFinishRequest(
    @JsonProperty("authenticationToken") String authenticationToken,
    @JsonProperty("publicKeyCredential") JsonNode publicKeyCredential
) {}
