package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.core.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/**
 * 화면 컨트롤러(admin.web 패키지)의 예외를 화면용 응답으로 바꾼다.
 * htmx 요청이면 실제 오류 상태 + 오류 토스트({@code HX-Trigger}, 본문 없음), 아니면 {@code admin/error} 페이지.
 *
 * <p>core 의 {@code GlobalExceptionHandler} 는 패키지 제한 없는 {@code @RestControllerAdvice} 라
 * 우선순위를 가장 높게 두어 화면 컨트롤러에서는 이 advice 가 먼저 잡게 한다.
 * REST 컨트롤러({@code /admin/api/**})의 JSON 오류 응답은 그대로다.
 *
 * <p>문구는 {@link ErrorMessages}(describeError.ts 이전)가 상태별로 정한다.
 */
@Slf4j
@ControllerAdvice(basePackages = "com.crosscert.passkey.admin.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebExceptionAdvice {

    @ExceptionHandler(BusinessException.class)
    public ModelAndView business(BusinessException e, HttpServletRequest req, HttpServletResponse res) {
        return respond(e, e.getErrorCode().getStatus().value(), req, res);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ModelAndView illegalArgument(IllegalArgumentException e, HttpServletRequest req, HttpServletResponse res) {
        return respond(e, HttpStatus.BAD_REQUEST.value(), req, res);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ModelAndView accessDenied(AccessDeniedException e, HttpServletRequest req, HttpServletResponse res) {
        return respond(e, HttpStatus.FORBIDDEN.value(), req, res);
    }

    /** 경로·요청 값의 타입 변환 실패(예: UUID 자리에 다른 문자열) — 클라이언트 오류 400. 원문(변환 예외 메시지)은 싣지 않는다. */
    @ExceptionHandler(TypeMismatchException.class)
    public ModelAndView typeMismatch(TypeMismatchException e, HttpServletRequest req, HttpServletResponse res) {
        return respond(e, HttpStatus.BAD_REQUEST.value(), req, res);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ModelAndView responseStatus(ResponseStatusException e, HttpServletRequest req, HttpServletResponse res) {
        return respond(e, e.getStatusCode().value(), req, res);
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView other(Exception e, HttpServletRequest req, HttpServletResponse res) {
        // Spring MVC 표준 예외(파라미터 누락 등)는 ErrorResponse 로 자기 상태 코드를 가진다.
        int status = (e instanceof ErrorResponse er) ? er.getStatusCode().value()
                : HttpStatus.INTERNAL_SERVER_ERROR.value();
        return respond(e, status, req, res);
    }

    private ModelAndView respond(Exception e, int status, HttpServletRequest req, HttpServletResponse res) {
        if (status >= 500) {
            log.error("[web] {} {} - status={}", req.getMethod(), req.getRequestURI(), status, e);
        } else {
            log.warn("[web] {} {} - status={} cause={} msg={}",
                    req.getMethod(), req.getRequestURI(), status, e.getClass().getSimpleName(), e.getMessage());
        }
        ErrorMessages.Info info = ErrorMessages.describe(e, status);
        if (Htmx.isHtmx(req)) {
            // 규칙 A: 실제 오류 상태를 유지한다. htmx 는 4xx/5xx 를 스왑하지 않고 afterRequest(successful=false)를 올리며,
            // HX-Trigger 헤더는 오류 응답에서도 처리된다(htmx 2.0.4 handleAjaxResponse).
            // 확인 대화상자(X-Confirm)의 요청은 토스트 없이 대화상자 안 인라인 오류로만 알린다(confirmError).
            if (Toasts.isConfirm(req)) Toasts.confirmError(res, info.toastMessage(), ErrorMessages.rawDetail(e, status, info), MDC.get("traceId"));
            else Toasts.error(res, info.toastMessage(), ErrorMessages.rawDetail(e, status, info), MDC.get("traceId"));
            res.setStatus(status);
            return null; // null = 응답 처리 완료(본문 없음)
        }
        ModelAndView mav = new ModelAndView(ErrorPage.VIEW);
        mav.setStatus(HttpStatusCode.valueOf(status)); // 499 같은 비표준 코드도 허용
        mav.addObject("status", status);
        mav.addObject("title", info.title());
        mav.addObject("message", info.detail() == null ? "" : info.detail());
        return mav;
    }
}
