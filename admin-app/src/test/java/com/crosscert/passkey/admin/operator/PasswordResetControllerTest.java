package com.crosscert.passkey.admin.operator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import com.crosscert.passkey.core.config.KstTime;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link PasswordResetController}, mirroring the
 * {@code *ControllerSecurityTest} pattern (nested {@code @SpringBootConfiguration}
 * to short-circuit {@code @WebMvcTest}'s upward search for {@code AdminApplication}
 * and its {@code @EnableJpaRepositories}; {@code AdminSecurityConfig} +
 * {@code GlobalExceptionHandler} imported so permitAll + CSRF-ignore for
 * {@code /admin/api/password-reset/**} are actually exercised; collaborators mocked).
 */
@WebMvcTest(controllers = PasswordResetController.class)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    com.crosscert.passkey.core.api.GlobalExceptionHandler.class
})
class PasswordResetControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(PasswordResetController.class)
    static class SliceConfig {
        // AdminSecurityConfig's adminLoginSuccessHandler bean needs a Clock.
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), KstTime.ZONE); }
    }

    @Autowired MockMvc mvc;
    @MockitoBean PasswordResetService service;

    // AdminSecurityConfig collaborators that are not otherwise supplied by the slice.
    @MockitoBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockitoBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockitoBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockitoBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    @MockitoBean com.crosscert.passkey.core.repository.AdminUserRepository users;
    // AdminSecurityConfig 협력 빈(adminLoginSuccessHandler 의 SecurityPolicyService, adminFilterChain 의 TenantBoundary).
    // 이 클래스는 자체 SliceConfig(실제 고정 Clock)를 쓰므로 AdminWebSliceTest 묶음(Clock mock 포함) 대신 개별로 둔다.
    @MockitoBean com.crosscert.passkey.admin.policy.SecurityPolicyService securityPolicyService;
    @MockitoBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;

    @Test
    void request_always_200_even_for_unknown() throws Exception {
        mvc.perform(post("/admin/api/password-reset/request").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost@x.com\"}"))
                .andExpect(status().isOk());
        verify(service).request(eq("ghost@x.com"));
    }

    @Test
    void confirm_delegates_to_service() throws Exception {
        mvc.perform(post("/admin/api/password-reset/confirm").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"newPassword\":\"NewPassw0rd!\"}"))
                .andExpect(status().isOk());
        verify(service).confirm(eq("t"), eq("NewPassw0rd!"));
    }

    @Test
    void confirm_invalid_token_returns_400() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("invalid token"))
            .when(service).confirm(eq("bad"), org.mockito.ArgumentMatchers.anyString());
        mvc.perform(post("/admin/api/password-reset/confirm").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"bad\",\"newPassword\":\"NewPassw0rd!\"}"))
            .andExpect(status().isBadRequest());
    }
}
