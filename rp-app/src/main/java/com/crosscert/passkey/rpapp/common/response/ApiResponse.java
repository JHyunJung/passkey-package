package com.crosscert.passkey.rpapp.common.response;

import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 모든 JSON 응답의 공통 봉투. success/code/message/data/error/traceId/timestamp 를 담는다. ok()/error() 팩터리로 생성. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        ErrorDetail error,
        String traceId,
        LocalDateTime timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "Success", data, null, MDC.get("traceId"), LocalDateTime.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, "OK", message, data, null, MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "OK", "Success", null, null, MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return new ApiResponse<>(false, code.code(), code.message(), null,
                new ErrorDetail(code.code(), null), MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, List<FieldError> fieldErrors) {
        // fieldErrors 는 필수 — null 이면 즉시 실패시켜 호출 측 실수를 빨리 드러낸다.
        Objects.requireNonNull(fieldErrors, "fieldErrors");
        return new ApiResponse<>(false, code.code(), code.message(), null,
                new ErrorDetail(code.code(), fieldErrors), MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, String detailMessage) {
        return new ApiResponse<>(false, code.code(), detailMessage, null,
                new ErrorDetail(code.code(), null), MDC.get("traceId"), LocalDateTime.now());
    }
}
