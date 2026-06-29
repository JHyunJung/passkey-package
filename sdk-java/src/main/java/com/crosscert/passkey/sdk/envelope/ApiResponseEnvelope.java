package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
