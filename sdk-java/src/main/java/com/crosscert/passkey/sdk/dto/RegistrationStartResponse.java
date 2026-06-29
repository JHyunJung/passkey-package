package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationStartResponse(
    @JsonProperty("registrationToken") String registrationToken,
    @JsonProperty("publicKeyCredentialCreationOptions") JsonNode publicKeyCredentialCreationOptions
) {}
