package com.crosscert.passkey.core.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e, HttpServletRequest req) {
        log.warn("[BusinessException] {} {} - code={} msg={}",
                 req.getMethod(), req.getRequestURI(), e.getErrorCode().getCode(), e.getMessage());
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        // Rejected values are intentionally omitted: they may echo sensitive user input
        // (passwords, tokens) back in the error body. Constraint messages are sufficient.
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), null, fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.MISSING_PARAMETER, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.TYPE_MISMATCH, e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "Malformed JSON request"));
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

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.getStatus())
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException e, HttpServletRequest req) {
        // Pick the envelope ErrorCode by HTTP status family so the code matches
        // the wire status (e.g., 404 → C003 ENTITY_NOT_FOUND, 409 → S001-style
        // would need explicit BusinessException — generic 4xx falls back to
        // INVALID_INPUT; 5xx to INTERNAL_SERVER_ERROR). Callers that need an
        // exact ErrorCode should throw BusinessException directly.
        int status = e.getStatusCode().value();
        ErrorCode code = status >= 500 ? ErrorCode.INTERNAL_SERVER_ERROR
                       : status == 404 ? ErrorCode.ENTITY_NOT_FOUND
                       : status == 401 ? ErrorCode.UNAUTHORIZED
                       : status == 403 ? ErrorCode.ACCESS_DENIED
                       : status == 405 ? ErrorCode.METHOD_NOT_ALLOWED
                       : ErrorCode.INVALID_INPUT;
        log.warn("[ResponseStatusException] {} {} - status={} code={} reason={}",
                 req.getMethod(), req.getRequestURI(), e.getStatusCode(), code.getCode(), e.getReason());
        String detail = e.getReason() != null ? e.getReason() : e.getMessage();
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiResponse.error(code, detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException e, HttpServletRequest req) {
        log.warn("[IllegalArgument] {} {} - {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("[Unhandled] {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
