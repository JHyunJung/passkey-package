package com.crosscert.passkey.rpapp.common.exception

import com.crosscert.passkey.rpapp.common.response.ApiResponse
import com.crosscert.passkey.rpapp.common.response.FieldError
import com.crosscert.passkey.sdk.exception.PasskeyApiException
import com.crosscert.passkey.sdk.exception.PasskeyAuthException
import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    // ── 템플릿 8 ───────────────────────────────────────────────────

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException, req: HttpServletRequest): ResponseEntity<ApiResponse<Void>> {
        log.warn("[BusinessException] {} {} - {}", req.method, req.requestURI, e.message)
        val code = e.errorCode
        return ResponseEntity.status(code.status).body(ApiResponse.error(code, e.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Void>> {
        val errors = e.bindingResult.fieldErrors
            // rejectedValue 를 echo 하지 않는다(core GlobalExceptionHandler 와 동일) —
            // 필드명 + 메시지로 충분하고, 입력값 반사를 피한다.
            .map { fe -> FieldError(fe.field, null, fe.defaultMessage) }
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT, errors))
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.MISSING_PARAMETER, e.message))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.TYPE_MISMATCH, e.message))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT, "Malformed JSON"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(e: HttpRequestMethodNotSupportedException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status)
            .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED, e.message))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(ErrorCode.ACCESS_DENIED.status)
            .body(ApiResponse.error(ErrorCode.ACCESS_DENIED))

    // ── SDK 예외 변환 4 ────────────────────────────────────────────

    @ExceptionHandler(PasskeyAuthException::class)
    fun handlePasskeyAuth(e: PasskeyAuthException): ResponseEntity<ApiResponse<Void>> {
        log.error("[PasskeyAuth] upstream rejected X-API-Key — check rp-app/.env", e)
        return ResponseEntity.status(ErrorCode.PASSKEY_AUTH_ERROR.status)
            .body(ApiResponse.error(ErrorCode.PASSKEY_AUTH_ERROR))
    }

    @ExceptionHandler(PasskeyRateLimitException::class)
    fun handlePasskeyRateLimit(e: PasskeyRateLimitException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(ErrorCode.PASSKEY_RATE_LIMITED.status)
            .header("Retry-After", e.retryAfterSeconds.toString())
            .body(ApiResponse.error(ErrorCode.PASSKEY_RATE_LIMITED))

    @ExceptionHandler(PasskeyIdTokenException::class)
    fun handlePasskeyIdToken(e: PasskeyIdTokenException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(ErrorCode.PASSKEY_ID_TOKEN.status)
            .body(ApiResponse.error(ErrorCode.PASSKEY_ID_TOKEN, e.message))

    @ExceptionHandler(PasskeyApiException::class)
    fun handlePasskeyApi(e: PasskeyApiException): ResponseEntity<ApiResponse<Void>> {
        log.error("[PasskeyApi] upstream code={} traceId={}", e.code, e.traceId, e)
        return ResponseEntity.status(ErrorCode.PASSKEY_API_ERROR.status)
            .body(ApiResponse.error(ErrorCode.PASSKEY_API_ERROR, e.message))
    }

    // ── 마지막 fallback ────────────────────────────────────────────

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, req: HttpServletRequest): ResponseEntity<ApiResponse<Void>> {
        log.error("[Unhandled] {} {}", req.method, req.requestURI, e)
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.status)
            .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR))
    }
}
