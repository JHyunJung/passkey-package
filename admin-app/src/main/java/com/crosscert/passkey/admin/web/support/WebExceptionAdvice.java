package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.core.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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

    /** 오류 원문(rawErrorDetail): {@code HTTP 상태 · [코드] 서버 메시지}. 예기치 못한 서버 오류는 내부 메시지를 싣지 않는다. */
    private static String rawDetail(Exception e, int status, ErrorMessages.Info info) {
        if (status >= 500 && !(e instanceof BusinessException)) return "HTTP " + status;
        StringBuilder sb = new StringBuilder("HTTP ").append(status);
        if (info.code() != null) sb.append(" · [").append(info.code()).append(']');
        String msg = e.getMessage();
        if (msg != null && !msg.isEmpty()) sb.append(' ').append(e instanceof ResponseStatusException r && r.getReason() != null ? r.getReason() : msg);
        return sb.toString();
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
            Toasts.error(res, info.toastMessage(), rawDetail(e, status, info), MDC.get("traceId"));
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
