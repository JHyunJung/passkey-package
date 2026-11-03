package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.core.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/**
 * 화면 컨트롤러(admin.web 패키지)의 예외를 화면용 응답으로 바꾼다.
 * htmx 요청이면 오류 토스트({@code HX-Trigger}) + 204, 아니면 {@code admin/error} 페이지.
 *
 * <p>core 의 {@code GlobalExceptionHandler} 는 패키지 제한 없는 {@code @RestControllerAdvice} 라
 * 우선순위를 가장 높게 두어 화면 컨트롤러에서는 이 advice 가 먼저 잡게 한다.
 * REST 컨트롤러({@code /admin/api/**})의 JSON 오류 응답은 그대로다.
 *
 * <p>문구는 Task 3 의 ErrorMessages 가 들어오기 전까지 고정 문구 하나를 쓴다.
 */
@Slf4j
@ControllerAdvice(basePackages = "com.crosscert.passkey.admin.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebExceptionAdvice {

    static final String DEFAULT_TITLE = "요청을 처리하지 못했습니다";
    static final String DEFAULT_MESSAGE = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";

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

    private ModelAndView respond(Exception e, int status, HttpServletRequest req, HttpServletResponse res) {
        if (status >= 500) {
            log.error("[web] {} {} - status={}", req.getMethod(), req.getRequestURI(), status, e);
        } else {
            log.warn("[web] {} {} - status={} cause={} msg={}",
                    req.getMethod(), req.getRequestURI(), status, e.getClass().getSimpleName(), e.getMessage());
        }
        if (Htmx.isHtmx(req)) {
            Toasts.error(res, DEFAULT_MESSAGE);
            res.setStatus(HttpStatus.NO_CONTENT.value());
            return null; // null = 응답 처리 완료(본문 없음)
        }
        ModelAndView mav = new ModelAndView(ErrorPage.VIEW);
        mav.setStatus(HttpStatus.valueOf(status));
        mav.addObject("status", status);
        mav.addObject("title", DEFAULT_TITLE);
        mav.addObject("message", DEFAULT_MESSAGE);
        return mav;
    }
}
