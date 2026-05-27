package com.crosscert.passkey.samplerp.common.response;

public record FieldError(String field, Object rejectedValue, String reason) {}
