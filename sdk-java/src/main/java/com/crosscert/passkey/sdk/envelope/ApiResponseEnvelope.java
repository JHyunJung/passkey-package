package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 서버 응답의 공통 봉투. {@code PasskeyClient} 메서드는 {@code data} 만 돌려주고 실패 봉투는
 * {@code PasskeyApiException} 으로 바꿔 던지므로, SDK 사용자가 직접 다룰 일은 거의 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponseEnvelope<T>(
    @JsonProperty("success") boolean success,
    @JsonProperty("code") String code,
    @JsonProperty("message") String message,
    @JsonProperty("data") T data,
    @JsonProperty("error") EnvelopeError error,
    @JsonProperty("traceId") String traceId,
    @JsonProperty("timestamp") String timestamp
) {}
