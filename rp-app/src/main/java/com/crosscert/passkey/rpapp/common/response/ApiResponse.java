package com.crosscert.passkey.rpapp.common.response;

import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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
        // 원본 Kotlin 의 non-null `List<FieldError>` 파라미터 계약을 보존(null 시 fail-fast).
        Objects.requireNonNull(fieldErrors, "fieldErrors");
        return new ApiResponse<>(false, code.code(), code.message(), null,
                new ErrorDetail(code.code(), fieldErrors), MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, String detailMessage) {
        return new ApiResponse<>(false, code.code(), detailMessage, null,
                new ErrorDetail(code.code(), null), MDC.get("traceId"), LocalDateTime.now());
    }
}
