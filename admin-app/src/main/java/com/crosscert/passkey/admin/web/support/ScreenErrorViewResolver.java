package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.admin.web.format.ErrorMessages;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * 컨테이너 오류 디스패치({@code /error}, Boot 의 BasicErrorController)가 화면 경로에서 났을 때 {@code admin/error} 를
 * 원래 상태 코드로 그린다(예: 템플릿 렌더링 실패 500). Boot 의 기본 ErrorViewResolver(templates/error/*.html)를 대신한다.
 *
 * <p>보안 체인은 화면 경로의 ERROR 디스패치만 허용한다({@code AdminSecurityConfig}). {@code /admin/api/**} 의
 * 오류 디스패치는 여기 오지 않지만, 혹시 오더라도 null 을 돌려 Boot 기본 처리에 맡긴다.
 */
@Component
public class ScreenErrorViewResolver implements ErrorViewResolver {

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (ScreenNotFoundAdvice.isApi(uri == null ? null : uri.toString())) return null;
        return errorView(status.value());
    }

    /** ErrorMessages(describeError.ts) 의 상태별 문구로 {@code admin/error}. 서버 내부 메시지는 싣지 않는다. */
    static ModelAndView errorView(int status) {
        ErrorMessages.Info info = ErrorMessages.describe(status, null);
        ModelAndView mav = new ModelAndView(ErrorPage.VIEW);
        mav.setStatus(HttpStatusCode.valueOf(status));
        mav.addObject("status", status);
        mav.addObject("title", info.title());
        // 상태별 쉬운 문구가 없는 4xx 는 detail 이 서버 원문 자리(null) — 빈 문자열로.
        mav.addObject("message", info.detail() == null || info.detailIsRaw() ? "" : info.detail());
        return mav;
    }
}
