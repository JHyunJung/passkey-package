package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeError(
    @JsonProperty("errorCode") String errorCode,
    @JsonProperty("fieldErrors") List<EnvelopeFieldError> fieldErrors
) {}
