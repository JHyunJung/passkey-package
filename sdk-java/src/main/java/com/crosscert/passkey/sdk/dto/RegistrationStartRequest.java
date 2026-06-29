package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RegistrationStartRequest(
    @JsonProperty("userHandle") String userHandle,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("username") String username
) {}
