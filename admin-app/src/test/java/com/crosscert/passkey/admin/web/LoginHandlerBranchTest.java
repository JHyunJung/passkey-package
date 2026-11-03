package com.crosscert.passkey.admin.web;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.AdminUserDetailsService;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 성공·실패 핸들러의 응답 분기. 화면 요청(htmx 또는 {@code _csrf} 파라미터)은 리다이렉트,
 * {@code X-XSRF-TOKEN} 헤더 방식(SPA·기존 통합 테스트)은 기존 JSON 그대로.
 */
@AdminWebSliceTest
class LoginHandlerBranchTest {

    private static final String EMAIL = "alice@example.com";
    private static final String GOOD = "right-password";

    @Autowired MockMvc mvc;
    @Autowired AdminUserDetailsService uds;
    @Autowired PasswordEncoder encoder;
    @Autowired AdminUserRepository users;
    @Autowired AuditLogService audit;
    @Autowired Clock clock;

    private AdminUser user;

    @BeforeEach
    void stubLogin() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-03T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(uds.loadUserByUsername(EMAIL)).thenReturn(new AdminUserDetails(
                UUID.randomUUID(), EMAIL, "hash", "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC()));
        when(encoder.matches(eq(GOOD), anyString())).thenReturn(true);
        user = new AdminUser(EMAIL, "hash", "PLATFORM_OPERATOR");
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    void loginJsonUnchanged() throws Exception {
        mvc.perform(post("/admin/login")
                .param("email", EMAIL).param("password", GOOD)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(EMAIL))
            .andExpect(jsonPath("$.role").value("PLATFORM_OPERATOR"))
            .andExpect(jsonPath("$.mfaRequired").value(false));
    }

    @Test
    void loginFormRedirects() throws Exception {
        mvc.perform(post("/admin/login")
                .param("email", EMAIL).param("password", GOOD)
                .with(csrf()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin"));
    }

    @Test
    void loginFormRedirectsMfaUserToLogin() throws Exception {
        user.setMfaEnabled(true);
        mvc.perform(post("/admin/login")
                .param("email", EMAIL).param("password", GOOD)
                .with(csrf()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void loginHtmxSuccessGetsHxRedirect() throws Exception {
        mvc.perform(post("/admin/login")
                .header("HX-Request", "true")
                .param("email", EMAIL).param("password", GOOD)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(header().string("HX-Redirect", "/admin"));
    }

    @Test
    void loginHtmxFailureRedirectsWithError() throws Exception {
        mvc.perform(post("/admin/login")
                .header("HX-Request", "true")
                .param("email", EMAIL).param("password", "wrong")
                .with(csrf().asHeader()))
            .andExpect(header().string("HX-Redirect", "/admin/login?error"));

        ArgumentCaptor<AuditAppendRequest> captor = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit, atLeastOnce()).append(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(r -> "ADMIN_LOGIN_FAILED".equals(r.action()));
    }

    @Test
    void loginJsonFailureUnchanged() throws Exception {
        mvc.perform(post("/admin/login")
                .param("email", EMAIL).param("password", "wrong")
                .with(csrf().asHeader()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
