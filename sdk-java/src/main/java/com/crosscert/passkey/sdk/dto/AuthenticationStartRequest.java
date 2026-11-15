package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code userHandle} 이 null 이면 필드를 보내지 않고, 서버는 사용자를 지정하지 않는 discoverable 로그인으로 처리한다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthenticationStartRequest(
    @JsonProperty("userHandle") String userHandle
) {}
