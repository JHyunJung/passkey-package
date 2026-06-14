package com.crosscert.passkey.rpapp.common.exception;

import com.crosscert.passkey.rpapp.common.response.ApiResponse;
import com.crosscert.passkey.rpapp.common.response.FieldError;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 템플릿 8 ───────────────────────────────────────────────────

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e, HttpServletRequest req) {
        log.warn("[BusinessException] {} {} - {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                // rejectedValue 를 echo 하지 않는다(core GlobalExceptionHandler 와 동일) —
                // 필드명 + 메시지로 충분하고, 입력값 반사를 피한다.
                .map(fe -> new FieldError(fe.getField(), null, fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT, errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.MISSING_PARAMETER, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.TYPE_MISMATCH, e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT, "Malformed JSON"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(ErrorCode.ACCESS_DENIED.getStatus())
                .body(ApiResponse.error(ErrorCode.ACCESS_DENIED));
    }

    // ── SDK 예외 변환 4 ────────────────────────────────────────────

    @ExceptionHandler(PasskeyAuthException.class)
    public ResponseEntity<ApiResponse<Void>> handlePasskeyAuth(PasskeyAuthException e) {
        log.error("[PasskeyAuth] upstream rejected X-API-Key — check rp-app/.env", e);
        return ResponseEntity.status(ErrorCode.PASSKEY_AUTH_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.PASSKEY_AUTH_ERROR));
    }

    @ExceptionHandler(PasskeyRateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handlePasskeyRateLimit(PasskeyRateLimitException e) {
        return ResponseEntity.status(ErrorCode.PASSKEY_RATE_LIMITED.getStatus())
                .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                .body(ApiResponse.error(ErrorCode.PASSKEY_RATE_LIMITED));
    }

    @ExceptionHandler(PasskeyIdTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handlePasskeyIdToken(PasskeyIdTokenException e) {
        return ResponseEntity.status(ErrorCode.PASSKEY_ID_TOKEN.getStatus())
                .body(ApiResponse.error(ErrorCode.PASSKEY_ID_TOKEN, e.getMessage()));
    }

    @ExceptionHandler(PasskeyApiException.class)
    public ResponseEntity<ApiResponse<Void>> handlePasskeyApi(PasskeyApiException e) {
        log.error("[PasskeyApi] upstream code={} traceId={}", e.getCode(), e.getTraceId(), e);
        return ResponseEntity.status(ErrorCode.PASSKEY_API_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.PASSKEY_API_ERROR, e.getMessage()));
    }

    // ── 마지막 fallback ────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("[Unhandled] {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
