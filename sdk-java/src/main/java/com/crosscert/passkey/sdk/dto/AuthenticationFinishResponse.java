package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationFinishResponse(
    @JsonProperty("idToken") String idToken,
    @JsonProperty("tokenType") String tokenType,
    @JsonProperty("expiresIn") long expiresIn
) {}
