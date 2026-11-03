package com.crosscert.passkey.admin.web;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 화면 경로의 인증·MFA 대기·CSRF 처리. /admin/api/** 의 JSON 동작이 그대로인지도 함께 본다. */
@AdminWebSliceTest
class PageSecurityTest {

    @Autowired MockMvc mvc;

    static Authentication operator() {
        AdminUserDetails principal = new AdminUserDetails(
                UUID.randomUUID(), "alice@example.com", "x",
                "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(principal, "x", principal.getAuthorities());
    }

    @Test
    void anonymousPageRedirectsToLogin() throws Exception {
        mvc.perform(get("/admin/tenants"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void anonymousHtmxGetsHxRedirect() throws Exception {
        mvc.perform(get("/admin/_/tenants/list").header("HX-Request", "true"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("HX-Redirect", "/admin/login"));
    }

    @Test
    void anonymousApiStill401Json() throws Exception {
        mvc.perform(get("/admin/api/tenants"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().json("{\"error\":\"unauthorized\"}", true));
    }

    @Test
    void staticAssetsPublic() throws Exception {
        mvc.perform(get("/admin/css/tokens.css"))
            .andExpect(status().isOk());
    }

    @Test
    void mfaPendingPageRedirects() throws Exception {
        mvc.perform(get("/admin/tenants")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void mfaPendingHtmxGetsHxRedirect() throws Exception {
        mvc.perform(get("/admin/_/tenants/list")
                .header("HX-Request", "true")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE))
            .andExpect(status().isOk())
            .andExpect(header().string("HX-Redirect", "/admin/login"));
    }

    @Test
    void mfaPendingAllowsLoginAndAssets() throws Exception {
        // 로그인 화면 컨트롤러는 Task 6 에서 생긴다. 지금은 MFA 게이트가 막지 않는지(403·리다이렉트 아님)만 본다.
        MvcResult login = mvc.perform(get("/admin/login")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE))
            .andReturn();
        int st = login.getResponse().getStatus();
        assertThat(st).isNotEqualTo(403);
        assertThat(st / 100).isNotEqualTo(3);

        mvc.perform(get("/admin/css/tokens.css")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE))
            .andExpect(status().isOk());
    }

    @Test
    void mfaPendingApiStill403Json() throws Exception {
        mvc.perform(get("/admin/api/tenants")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE))
            .andExpect(status().isForbidden())
            .andExpect(content().json("{\"error\":\"mfa_required\"}", true));
    }

    @Test
    void pagePostWithoutCsrfRejected() throws Exception {
        mvc.perform(post("/admin/_/tenants").with(authentication(operator())))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("권한이 없습니다")));
    }

    @Test
    void htmxPostWithoutCsrfGetsErrorToast() throws Exception {
        mvc.perform(post("/admin/_/tenants").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isForbidden())
            .andExpect(header().string("HX-Trigger", containsString("\"kind\":\"error\"")));
    }

    @Test
    void apiPostWithoutCsrfStill403Json() throws Exception {
        mvc.perform(post("/admin/api/tenants").with(authentication(operator())))
            .andExpect(status().isForbidden())
            .andExpect(content().json("{\"error\":\"forbidden\"}", true));
    }
}
