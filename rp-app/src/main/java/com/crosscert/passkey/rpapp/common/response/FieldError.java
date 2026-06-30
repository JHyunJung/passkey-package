package com.crosscert.passkey.rpapp.common.response;

/** 입력 검증 실패 시 필드 단위 오류 1건. */
public record FieldError(String field, Object rejectedValue, String reason) {}
