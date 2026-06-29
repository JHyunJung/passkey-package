package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthenticationStartRequest(
    @JsonProperty("userHandle") String userHandle
) {}
