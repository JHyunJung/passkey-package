package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code idToken} 은 iss·aud 까지 보는
 * {@link com.crosscert.passkey.sdk.PasskeyClient#verifyIdToken(String, String, String)} 으로 검증한 뒤 쓴다
 * (1-인자 오버로드는 iss·aud 를 보지 않는다). {@code expiresIn} 은 토큰 수명(초).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationFinishResponse(
    @JsonProperty("idToken") String idToken,
    @JsonProperty("tokenType") String tokenType,
    @JsonProperty("expiresIn") long expiresIn
) {}
