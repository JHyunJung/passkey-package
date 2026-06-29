package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationFinishResponse(
    @JsonProperty("credentialId") String credentialId,
    @JsonProperty("aaguid") String aaguid,
    @JsonProperty("attestationFormat") String attestationFormat,
    @JsonProperty("createdAt") String createdAt
) {}
