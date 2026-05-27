package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthenticationStartRequest(String userHandle) {}
