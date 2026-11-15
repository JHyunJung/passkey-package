package com.crosscert.passkey.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;

/**
 * JSON API 응답 봉투. RP 쪽 sdk-java {@code ApiResponseEnvelope} 가 같은 필드 이름으로 읽으므로 이름을 바꾸면 연동이 깨진다.
 * null 필드는 응답에서 빠진다. traceId 는 {@link TraceIdFilter} 가 MDC 에 넣은 값이라 그 필터 밖에서 만들면 null 이다.
 * {@code error(code, detailMessage)} 는 ErrorCode 기본 문구 대신 detailMessage 를 message 에 넣는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        ErrorDetail error,
        String traceId,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "Success", data, null, currentTraceId(), Instant.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, "OK", message, data, null, currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "OK", "Success", null, null, currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return new ApiResponse<>(false, code.getCode(), code.getMessage(), null,
                new ErrorDetail(code.getCode(), null), currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, String detailMessage) {
        return new ApiResponse<>(false, code.getCode(), detailMessage, null,
                new ErrorDetail(code.getCode(), null), currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, List<FieldError> fieldErrors) {
        return new ApiResponse<>(false, code.getCode(), code.getMessage(), null,
                new ErrorDetail(code.getCode(), fieldErrors), currentTraceId(), Instant.now());
    }

    private static String currentTraceId() {
        return MDC.get("traceId");
    }
}
