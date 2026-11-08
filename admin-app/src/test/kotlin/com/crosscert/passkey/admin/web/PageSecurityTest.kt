package com.crosscert.passkey.admin.web

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.util.UUID

// 화면 경로의 인증·MFA 대기·CSRF 처리. /admin/api/** 의 JSON 동작이 그대로인지도 함께 본다.
// (Kotlin 전환) 블록 주석 안의 "/**" 는 Kotlin 에서 중첩 주석을 열기 때문에 줄 주석으로 둔다.
@AdminWebSliceTest
class PageSecurityTest {

    @Autowired lateinit var mvc: MockMvc

    companion object {
        fun operator(): Authentication {
            val principal = AdminUserDetails(
                UUID.randomUUID(), "alice@example.com", "x",
                "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
            )
            return UsernamePasswordAuthenticationToken(principal, "x", principal.authorities)
        }
    }

    @Test
    fun anonymousPageRedirectsToLogin() {
        mvc.perform(get("/admin/tenants"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/login"))
    }

    @Test
    fun anonymousHtmxGetsHxRedirect() {
        mvc.perform(get("/admin/_/tenants/list").header("HX-Request", "true"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("HX-Redirect", "/admin/login"))
    }

    @Test
    fun anonymousApiStill401Json() {
        mvc.perform(get("/admin/api/tenants"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().json("{\"error\":\"unauthorized\"}", true))
    }

    @Test
    fun staticAssetsPublic() {
        mvc.perform(get("/admin/css/tokens.css"))
            .andExpect(status().isOk())
    }

    @Test
    fun mfaPendingPageRedirects() {
        mvc.perform(
            get("/admin/tenants")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE),
        )
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/login"))
    }

    @Test
    fun mfaPendingHtmxGetsHxRedirect() {
        mvc.perform(
            get("/admin/_/tenants/list")
                .header("HX-Request", "true")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("HX-Redirect", "/admin/login"))
    }

    @Test
    fun mfaPendingAllowsLoginAndAssets() {
        // 로그인 화면 컨트롤러는 Task 6 에서 생긴다. 지금은 MFA 게이트가 막지 않는지(403·리다이렉트 아님)만 본다.
        val login = mvc.perform(
            get("/admin/login")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE),
        )
            .andReturn()
        val st = login.response.status
        assertThat(st).isNotEqualTo(403)
        assertThat(st / 100).isNotEqualTo(3)

        mvc.perform(
            get("/admin/css/tokens.css")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE),
        )
            .andExpect(status().isOk())
    }

    @Test
    fun mfaPendingApiStill403Json() {
        mvc.perform(
            get("/admin/api/tenants")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE),
        )
            .andExpect(status().isForbidden())
            .andExpect(content().json("{\"error\":\"mfa_required\"}", true))
    }

    @Test
    fun pagePostWithoutCsrfRejected() {
        mvc.perform(post("/admin/_/tenants").with(authentication(operator())))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("권한이 없습니다")))
    }

    @Test
    fun htmxPostWithoutCsrfGetsErrorPageAndToast() {
        mvc.perform(post("/admin/_/tenants").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isForbidden())
            .andExpect(header().string("HX-Trigger", containsString("\"kind\":\"error\"")))
            .andExpect(content().string(containsString("권한이 없습니다")))
    }

    @Test
    fun pageDeniedWithJsonAcceptStillGetsErrorPage() {
        mvc.perform(
            post("/admin/_/tenants")
                .accept("application/json")
                .with(authentication(operator())),
        )
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(containsString("권한이 없습니다")))
    }

    @Test
    fun apiPostWithoutCsrfStill403Json() {
        mvc.perform(post("/admin/api/tenants").with(authentication(operator())))
            .andExpect(status().isForbidden())
            .andExpect(content().json("{\"error\":\"forbidden\"}", true))
    }
}
