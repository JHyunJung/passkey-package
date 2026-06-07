package com.crosscert.passkey.rpapp.common.response;

public record FieldError(String field, Object rejectedValue, String reason) {}
