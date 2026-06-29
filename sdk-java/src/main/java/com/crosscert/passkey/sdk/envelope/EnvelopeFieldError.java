package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeFieldError(
    @JsonProperty("field") String field,
    @JsonProperty("rejectedValue") Object rejectedValue,
    @JsonProperty("reason") String reason
) {}
