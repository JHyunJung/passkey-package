package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeFieldError(String field, Object rejectedValue, String reason) {}
