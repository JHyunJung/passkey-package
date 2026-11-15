package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code userHandle} 은 RP 가 정한 사용자 식별자를 base64url 로 인코딩한 값으로, 디코드해 1~64바이트여야 한다. */
public record RegistrationStartRequest(
    @JsonProperty("userHandle") String userHandle,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("username") String username
) {}
