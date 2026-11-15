package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code idToken} 은 {@code verifyIdToken} 으로 검증한 뒤 쓴다. {@code expiresIn} 은 토큰 수명(초). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationFinishResponse(
    @JsonProperty("idToken") String idToken,
    @JsonProperty("tokenType") String tokenType,
    @JsonProperty("expiresIn") long expiresIn
) {}
