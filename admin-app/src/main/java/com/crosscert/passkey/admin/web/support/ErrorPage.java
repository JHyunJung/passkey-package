package com.crosscert.passkey.admin.web.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

import java.util.Map;

/**
 * {@code admin/error} 템플릿. 컨트롤러 밖(보안 필터의 AccessDeniedHandler)에서도
 * 같은 템플릿을 렌더링할 수 있도록 ViewResolver 로 직접 그리는 도우미를 둔다.
 */
public final class ErrorPage {

    public static final String VIEW = "admin/error";

    private ErrorPage() {}

    /** @return 렌더링했으면 true, 템플릿 뷰를 찾지 못했으면 false(호출자가 다른 응답을 쓴다). */
    public static boolean render(ViewResolver resolver, HttpServletRequest req, HttpServletResponse res,
                                 int status, String title, String message) throws Exception {
        View view = resolver.resolveViewName(VIEW, req.getLocale());
        if (view == null) return false;
        res.setStatus(status);
        view.render(Map.of("status", status, "title", title, "message", message), req, res);
        return true;
    }
}
