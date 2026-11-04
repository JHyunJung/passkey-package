package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.api.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 처리기가 없는 주소(정적 리소스 처리기의 {@link NoResourceFoundException}).
 *
 * <p>이 예외는 화면 컨트롤러가 아니라 정적 리소스 처리기에서 나므로 {@link WebExceptionAdvice}(admin.web 한정)가
 * 받지 못하고, core 의 {@code GlobalExceptionHandler} 가 404 JSON 으로 답했다. 인증된 운영자가 화면 경로
 * (예: {@code /admin/tenants/x/y})에서 JSON 을 보게 되므로 화면 경로만 {@code admin/error} 404 로 바꾼다.
 * <ul>
 *   <li>{@code /admin/api/**} 와 {@code /admin} 밖의 경로(예: {@code /favicon.ico}): {@code GlobalExceptionHandler.handleNoResource}
 *       에 그대로 맡긴다(응답 동일).</li>
 *   <li>htmx 요청: 404 + 오류 토스트, 본문 없음(규칙 A).</li>
 *   <li>그 밖: {@code admin/error} 404.</li>
 * </ul>
 * 패키지 제한 없는 advice 라 모든 슬라이스에 올라오므로 협력 빈은 {@link ObjectProvider} 로 받는다.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScreenNotFoundAdvice {

    private final ObjectProvider<GlobalExceptionHandler> apiHandler;

    public ScreenNotFoundAdvice(ObjectProvider<GlobalExceptionHandler> apiHandler) {
        this.apiHandler = apiHandler;
    }

    /** @return API 면 ResponseEntity(JSON), 화면이면 ModelAndView, htmx 면 null(응답 처리 완료). */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object notFound(NoResourceFoundException e, HttpServletRequest req, HttpServletResponse res) {
        if (!isScreen(req.getRequestURI())) {
            GlobalExceptionHandler h = apiHandler.getIfAvailable();
            if (h != null) return h.handleNoResource(e, req);
            return ResponseEntity.status(ErrorCode.ENTITY_NOT_FOUND.getStatus())
                    .body(ApiResponse.error(ErrorCode.ENTITY_NOT_FOUND));
        }
        ErrorMessages.Info info = ErrorMessages.describe(HttpStatus.NOT_FOUND.value(), null);
        if (Htmx.isHtmx(req)) {
            Toasts.error(res, info.toastMessage(), "HTTP 404", null);
            res.setStatus(HttpStatus.NOT_FOUND.value());
            return null;
        }
        return ScreenErrorViewResolver.errorView(HttpStatus.NOT_FOUND.value());
    }

    /** 화면 경로: {@code /admin} 아래이고 {@code /admin/api} 가 아닌 주소. */
    static boolean isScreen(String uri) {
        return uri != null && (uri.equals("/admin") || uri.startsWith("/admin/")) && !isApi(uri);
    }

    static boolean isApi(String uri) {
        return uri != null && (uri.equals("/admin/api") || uri.startsWith("/admin/api/"));
    }
}
