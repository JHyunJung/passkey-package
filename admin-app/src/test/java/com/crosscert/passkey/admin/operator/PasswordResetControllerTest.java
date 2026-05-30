package com.crosscert.passkey.admin.operator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC); }
    }

    @Autowired MockMvc mvc;
    @MockBean PasswordResetService service;

    // AdminSecurityConfig collaborators that are not otherwise supplied by the slice.
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository users;

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
}
