package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeError(String errorCode, List<EnvelopeFieldError> fieldErrors) {}
