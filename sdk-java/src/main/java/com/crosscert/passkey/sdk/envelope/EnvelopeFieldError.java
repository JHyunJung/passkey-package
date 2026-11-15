package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 서버 입력 검증 실패의 필드별 오류 1건. {@code PasskeyApiException.getFieldErrors()} 로 받는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeFieldError(
    @JsonProperty("field") String field,
    @JsonProperty("rejectedValue") Object rejectedValue,
    @JsonProperty("reason") String reason
) {}
