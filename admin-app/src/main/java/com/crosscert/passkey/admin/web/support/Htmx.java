package com.crosscert.passkey.admin.web.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;

/** htmx 요청 판별과 리다이렉트. 필터 단계(보안 핸들러)와 화면 컨트롤러가 함께 쓴다. */
public final class Htmx {

    public static final String HX_REQUEST = "HX-Request";
    public static final String HX_REDIRECT = "HX-Redirect";

    private Htmx() {}

    /** htmx 가 보낸 요청이면 {@code HX-Request: true} 헤더가 붙는다. */
    public static boolean isHtmx(HttpServletRequest req) {
        return "true".equalsIgnoreCase(req.getHeader(HX_REQUEST));
    }

    /**
     * 현재 요청(RequestContextHolder)을 기준으로 {@link #redirect(HttpServletRequest, HttpServletResponse, String)}.
     * 화면 컨트롤러용. 요청이 바인딩되지 않은 곳(보안 필터 핸들러 등)은 요청을 직접 넘기는 쪽을 쓴다.
     */
    public static void redirect(HttpServletResponse res, String path) throws IOException {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            throw new IllegalStateException("no current request bound; use redirect(req, res, path)");
        }
        redirect(attrs.getRequest(), res, path);
    }

    /**
     * htmx 요청이면 {@code HX-Redirect} 헤더 + 200 (htmx 가 전체 페이지 이동을 한다),
     * 아니면 302 리다이렉트.
     */
    public static void redirect(HttpServletRequest req, HttpServletResponse res, String path) throws IOException {
        if (isHtmx(req)) {
            res.setStatus(HttpServletResponse.SC_OK);
            res.setHeader(HX_REDIRECT, path);
        } else {
            res.sendRedirect(path);
        }
    }
}
