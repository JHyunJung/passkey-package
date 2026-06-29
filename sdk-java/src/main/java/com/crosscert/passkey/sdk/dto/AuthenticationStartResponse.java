package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationStartResponse(
    @JsonProperty("authenticationToken") String authenticationToken,
    @JsonProperty("publicKeyCredentialRequestOptions") JsonNode publicKeyCredentialRequestOptions
) {}
