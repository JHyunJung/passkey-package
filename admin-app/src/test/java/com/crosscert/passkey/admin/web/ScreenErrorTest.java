package com.crosscert.passkey.admin.web;

import com.crosscert.passkey.admin.web.support.ScreenErrorViewResolver;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 오류 화면: 화면 경로의 404·컨테이너 오류 디스패치는 원래 상태의 admin/error, /admin/api/** 는 이전과 같은 응답.
 *
 * <p>이 브랜치 이전(Task 6 전) 측정값 — 이 테스트가 "그대로"로 고정하는 값:
 * <ul>
 *   <li>GET /admin/api/nope(인증): 404 application/json {"success":false,"code":"C003","message":"Entity not found",…}</li>
 *   <li>GET /admin/api/nope(미인증): 401 {"error":"unauthorized"}</li>
 *   <li>/admin/api/** 에서 난 ERROR 디스패치: 403(denyAll → 접근 거부 처리기)</li>
 *   <li>GET /error 직접 요청: 403, GET /admin/nope(규칙 밖 경로): 403</li>
 * </ul>
 * 바뀐 값(화면): 인증된 GET /admin/tenants/a/b/c 404 JSON → 404 admin/error, 화면 ERROR 디스패치 403 → 원래 상태(404/500),
 * 미인증 화면 ERROR 디스패치 302(/admin/login) → 원래 상태.
 * MockMvc 는 sendError 를 /error 로 넘기지 않으므로 ERROR 디스패치는 요청 속성으로 흉내 낸다.
 */
@AdminWebSliceTest
@Import(ScreenErrorViewResolver.class)
class ScreenErrorTest {

    @Autowired MockMvc mvc;

    private static MockHttpServletRequestBuilder errorDispatch(String originalUri, int status, boolean authenticated) {
        MockHttpServletRequestBuilder b = get("/error").accept("text/html").with(req -> {
            req.setDispatcherType(DispatcherType.ERROR);
            req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
            req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
            return req;
        });
        return authenticated ? b.with(authentication(PageSecurityTest.operator())) : b;
    }

    // ── /admin/api/** : 그대로 ──

    @Test
    void apiUnknownPathStill404Json() throws Exception {
        mvc.perform(get("/admin/api/nope").with(authentication(PageSecurityTest.operator())))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("C003"))
            .andExpect(jsonPath("$.message").value("Entity not found"))
            .andExpect(jsonPath("$.error.errorCode").value("C003"));
    }

    @Test
    void apiUnknownPathAnonymousStill401Json() throws Exception {
        mvc.perform(get("/admin/api/nope"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().json("{\"error\":\"unauthorized\"}", true));
    }

    @Test
    void apiErrorDispatchUnchanged() throws Exception {
        mvc.perform(errorDispatch("/admin/api/tenants", 500, true))
            .andExpect(status().isForbidden());
    }

    @Test
    void directErrorRequestStillDenied() throws Exception {
        mvc.perform(get("/error").with(authentication(PageSecurityTest.operator())))
            .andExpect(status().isForbidden());
    }

    @Test
    void pathOutsideRulesStillDenied() throws Exception {
        // 보안 규칙(anyRequest().denyAll())은 바꾸지 않는다.
        mvc.perform(get("/admin/nope").with(authentication(PageSecurityTest.operator())))
            .andExpect(status().isForbidden());
    }

    // ── 화면 경로 ──

    @Test
    void screenUnknownPathRendersErrorPage404() throws Exception {
        mvc.perform(get("/admin/tenants/a/b/c").with(authentication(PageSecurityTest.operator())))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(containsString("찾을 수 없습니다")))
            .andExpect(content().string(not(containsString("권한이 없습니다"))));
    }

    @Test
    void screenUnknownPathHtmxGets404Toast() throws Exception {
        mvc.perform(get("/admin/tenants/a/b/c").header("HX-Request", "true").with(authentication(PageSecurityTest.operator())))
            .andExpect(status().isNotFound())
            .andExpect(header().string("HX-Trigger", containsString("\"kind\":\"error\"")))
            .andExpect(content().string(emptyString()));
    }

    @Test
    void screenErrorDispatchKeepsStatus404() throws Exception {
        mvc.perform(errorDispatch("/admin/tenants/x", 404, true))
            .andExpect(status().isNotFound())
            .andExpect(content().string(containsString("찾을 수 없습니다")))
            .andExpect(content().string(not(containsString("권한이 없습니다"))));
    }

    @Test
    void screenErrorDispatchKeepsStatus500() throws Exception {
        mvc.perform(errorDispatch("/admin/tenants/x", 500, true))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(containsString("서버가 잠시 응답하지 않습니다")));
    }

    @Test
    void anonymousScreenErrorDispatchShowsErrorNotLoginRedirect() throws Exception {
        mvc.perform(errorDispatch("/admin/login", 500, false))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(containsString("서버가 잠시 응답하지 않습니다")));
    }

    @Test
    void anonymousScreenStillRedirectsToLogin() throws Exception {
        mvc.perform(get("/admin/tenants/a/b/c"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/login"));
    }
}
