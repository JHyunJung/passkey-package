package com.crosscert.passkey.admin.web.support

import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.core.api.BusinessException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.TypeMismatchException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView

private val log = LoggerFactory.getLogger(WebExceptionAdvice::class.java)

/**
 * 화면 컨트롤러(admin.web 패키지)의 예외를 화면용 응답으로 바꾼다.
 * htmx 요청이면 실제 오류 상태 + 오류 토스트(`HX-Trigger`, 본문 없음), 아니면 `admin/error` 페이지.
 *
 * core 의 `GlobalExceptionHandler` 는 패키지 제한 없는 `@RestControllerAdvice` 라
 * 우선순위를 가장 높게 두어 화면 컨트롤러에서는 이 advice 가 먼저 잡게 한다.
 * REST 컨트롤러(`/admin/api/` 아래)의 JSON 오류 응답은 그대로다.
 *
 * 문구는 [ErrorMessages](describeError.ts 이전)가 상태별로 정한다.
 */
@ControllerAdvice(basePackages = ["com.crosscert.passkey.admin.web"])
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebExceptionAdvice {

    @ExceptionHandler(BusinessException::class)
    fun business(e: BusinessException, req: HttpServletRequest, res: HttpServletResponse): ModelAndView? =
        respond(e, e.errorCode.status.value(), req, res)

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArgument(e: IllegalArgumentException, req: HttpServletRequest, res: HttpServletResponse): ModelAndView? =
        respond(e, HttpStatus.BAD_REQUEST.value(), req, res)

    @ExceptionHandler(AccessDeniedException::class)
    fun accessDenied(e: AccessDeniedException, req: HttpServletRequest, res: HttpServletResponse): ModelAndView? =
        respond(e, HttpStatus.FORBIDDEN.value(), req, res)

    /** 경로·요청 값의 타입 변환 실패(예: UUID 자리에 다른 문자열) — 클라이언트 오류 400. 원문(변환 예외 메시지)은 싣지 않는다. */
    @ExceptionHandler(TypeMismatchException::class)
    fun typeMismatch(e: TypeMismatchException, req: HttpServletRequest, res: HttpServletResponse): ModelAndView? =
        respond(e, HttpStatus.BAD_REQUEST.value(), req, res)

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(e: ResponseStatusException, req: HttpServletRequest, res: HttpServletResponse): ModelAndView? =
        respond(e, e.statusCode.value(), req, res)

    @ExceptionHandler(Exception::class)
    fun other(e: Exception, req: HttpServletRequest, res: HttpServletResponse): ModelAndView? {
        // Spring MVC 표준 예외(파라미터 누락 등)는 ErrorResponse 로 자기 상태 코드를 가진다.
        val status = if (e is ErrorResponse) e.statusCode.value() else HttpStatus.INTERNAL_SERVER_ERROR.value()
        return respond(e, status, req, res)
    }

    private fun respond(e: Exception, status: Int, req: HttpServletRequest, res: HttpServletResponse): ModelAndView? {
        if (status >= 500) {
            log.error("[web] {} {} - status={}", req.method, req.requestURI, status, e)
        } else {
            log.warn(
                "[web] {} {} - status={} cause={} msg={}",
                req.method, req.requestURI, status, e.javaClass.simpleName, e.message,
            )
        }
        val info = ErrorMessages.describe(e, status)
        if (Htmx.isHtmx(req)) {
            // 규칙 A: 실제 오류 상태를 유지한다. htmx 는 4xx/5xx 를 스왑하지 않고 afterRequest(successful=false)를 올리며,
            // HX-Trigger 헤더는 오류 응답에서도 처리된다(htmx 2.0.4 handleAjaxResponse).
            // 확인 대화상자(X-Confirm)의 요청은 토스트 없이 대화상자 안 인라인 오류로만 알린다(confirmError).
            if (Toasts.isConfirm(req)) {
                Toasts.confirmError(res, info.toastMessage(), ErrorMessages.rawDetail(e, status, info), MDC.get("traceId"))
            } else {
                Toasts.error(res, info.toastMessage(), ErrorMessages.rawDetail(e, status, info), MDC.get("traceId"))
            }
            res.status = status
            return null // null = 응답 처리 완료(본문 없음)
        }
        val mav = ModelAndView(ErrorPage.VIEW)
        mav.status = HttpStatusCode.valueOf(status) // 499 같은 비표준 코드도 허용
        mav.addObject("status", status)
        mav.addObject("title", info.title)
        mav.addObject("message", info.detail ?: "")
        return mav
    }
}
