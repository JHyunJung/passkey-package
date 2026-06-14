package com.crosscert.passkey.rpapp.common.response

import com.crosscert.passkey.rpapp.common.exception.ErrorCode
import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.MDC
import java.time.LocalDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val code: String?,
    val message: String?,
    val data: T?,
    val error: ErrorDetail?,
    val traceId: String?,
    val timestamp: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun <T> ok(data: T?): ApiResponse<T> =
            ApiResponse(true, "OK", "Success", data, null, MDC.get("traceId"), LocalDateTime.now())

        @JvmStatic
        fun <T> ok(message: String?, data: T?): ApiResponse<T> =
            ApiResponse(true, "OK", message, data, null, MDC.get("traceId"), LocalDateTime.now())

        @JvmStatic
        fun ok(): ApiResponse<Void> =
            ApiResponse(true, "OK", "Success", null, null, MDC.get("traceId"), LocalDateTime.now())

        @JvmStatic
        fun error(code: ErrorCode): ApiResponse<Void> =
            ApiResponse(
                false, code.code, code.message, null,
                ErrorDetail(code.code, null), MDC.get("traceId"), LocalDateTime.now(),
            )

        @JvmStatic
        fun error(code: ErrorCode, fieldErrors: List<FieldError>): ApiResponse<Void> =
            ApiResponse(
                false, code.code, code.message, null,
                ErrorDetail(code.code, fieldErrors), MDC.get("traceId"), LocalDateTime.now(),
            )

        @JvmStatic
        fun error(code: ErrorCode, detailMessage: String?): ApiResponse<Void> =
            ApiResponse(
                false, code.code, detailMessage, null,
                ErrorDetail(code.code, null), MDC.get("traceId"), LocalDateTime.now(),
            )
    }
}
