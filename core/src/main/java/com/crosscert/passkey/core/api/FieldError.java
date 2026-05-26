package com.crosscert.passkey.core.api;

public record FieldError(String field, Object rejectedValue, String reason) {}
