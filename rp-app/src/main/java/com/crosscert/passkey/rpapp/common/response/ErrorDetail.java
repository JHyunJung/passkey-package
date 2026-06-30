package com.crosscert.passkey.rpapp.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 에러 응답의 상세. 에러 코드와 (검증 실패 시) 필드별 오류 목록. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(String errorCode, List<FieldError> fieldErrors) {}
