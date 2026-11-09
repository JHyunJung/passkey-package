package com.crosscert.passkey.admin.web

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.AdminUserDetailsService
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * 로그인 성공·실패 핸들러의 응답 분기. 화면 요청(htmx 또는 `_csrf` 파라미터)은 리다이렉트,
 * `X-XSRF-TOKEN` 헤더 방식(SPA·기존 통합 테스트)은 기존 JSON 그대로.
 */
@AdminWebSliceTest
class LoginHandlerBranchTest {

    companion object {
        private const val EMAIL = "alice@example.com"
        private const val GOOD = "right-password"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var uds: AdminUserDetailsService
    @Autowired lateinit var encoder: PasswordEncoder
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var audit: AuditLogService
    @Autowired lateinit var clock: Clock

    private lateinit var user: AdminUser

    @BeforeEach
    fun stubLogin() {
        whenever(clock.instant()).thenReturn(Instant.parse("2026-11-03T00:00:00Z"))
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(uds.loadUserByUsername(EMAIL)).thenReturn(
            AdminUserDetails(UUID.randomUUID(), EMAIL, "hash", "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC()),
        )
        whenever(encoder.matches(eq(GOOD), anyString())).thenReturn(true)
        user = AdminUser(EMAIL, "hash", "PLATFORM_OPERATOR")
        whenever(users.findByEmail(EMAIL)).thenReturn(Optional.of(user))
    }

    @Test
    fun loginJsonUnchanged() {
        mvc.perform(
            post("/admin/login")
                .param("email", EMAIL).param("password", GOOD)
                .with(csrf().asHeader()),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("\$.email").value(EMAIL))
            .andExpect(jsonPath("\$.role").value("PLATFORM_OPERATOR"))
            .andExpect(jsonPath("\$.mfaRequired").value(false))
    }

    @Test
    fun loginFormRedirects() {
        mvc.perform(
            post("/admin/login")
                .param("email", EMAIL).param("password", GOOD)
                .with(csrf()),
        )
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin"))
    }

    @Test
    fun loginFormRedirectsMfaUserToLogin() {
        user.setMfaEnabled(true)
        mvc.perform(
            post("/admin/login")
                .param("email", EMAIL).param("password", GOOD)
                .with(csrf()),
        )
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/login"))
    }

    @Test
    fun loginHtmxSuccessGetsHxRedirect() {
        mvc.perform(
            post("/admin/login")
                .header("HX-Request", "true")
                .param("email", EMAIL).param("password", GOOD)
                .with(csrf().asHeader()),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("HX-Redirect", "/admin"))
    }

    @Test
    fun loginHtmxFailureRedirectsWithError() {
        mvc.perform(
            post("/admin/login")
                .header("HX-Request", "true")
                .param("email", EMAIL).param("password", "wrong")
                .with(csrf().asHeader()),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("HX-Redirect", "/admin/login?error"))

        val captor = argumentCaptor<AuditAppendRequest>()
        verify(audit, atLeastOnce()).append(captor.capture())
        assertThat(captor.allValues).anyMatch { r -> "ADMIN_LOGIN_FAILED" == r.action }
    }

    @Test
    fun loginJsonFailureUnchanged() {
        mvc.perform(
            post("/admin/login")
                .param("email", EMAIL).param("password", "wrong")
                .with(csrf().asHeader()),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("\$.error").value("unauthorized"))
    }
}
