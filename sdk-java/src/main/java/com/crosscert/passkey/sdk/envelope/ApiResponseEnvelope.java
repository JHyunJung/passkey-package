package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponseEnvelope<T>(
        boolean success,
        String code,
        String message,
        T data,
        EnvelopeError error,
        String traceId,
        String timestamp
) {}
