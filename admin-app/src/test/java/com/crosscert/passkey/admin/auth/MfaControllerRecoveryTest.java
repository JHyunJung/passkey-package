package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MfaController} covering the two NEW behaviors
 * wired in this task: recovery-code issuance on {@code confirm()} and the
 * TOTP-failure → recovery-code fallback on {@code verify()}.
 *
 * <p>Mirrors {@link MfaControllerSecurityTest}'s slice setup exactly (nested
 * {@code @SpringBootConfiguration}, real {@link TotpService} + fixed {@link Clock},
 * same security imports + MockBeans) so the security/CSRF/principal wiring is
 * identical. Here {@link TotpService} is replaced by a mock via {@code @MockBean}
 * to deterministically force TOTP success/failure, and {@link MfaSecretCipher} /
 * {@link RecoveryCodeService} are mocked.
 */
@WebMvcTest(controllers = MfaController.class)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    com.crosscert.passkey.core.api.GlobalExceptionHandler.class
})
class MfaControllerRecoveryTest {

    private static final long FIXED_MILLIS = 1_700_000_000_000L;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MfaController.class)
    static class SliceConfig {
        @Bean Clock clock() { return Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC); }
    }

    @Autowired MockMvc mvc;

    @MockBean TotpService totp;
    @MockBean MfaSecretCipher secretCipher;
    @MockBean RecoveryCodeService recoveryCodes;
    @MockBean AdminUserRepository admins;
    // AdminSecurityConfig collaborators that are not otherwise supplied by the slice.
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;

    private static AdminUser adminUserWithUuid() {
        var u = new AdminUser("alice@example.com", "x", "ADMIN");
        try {
            var f = AdminUser.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return u;
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void confirm_validCode_returnsRecoveryCodesInBody() throws Exception {
        AdminUser u = adminUserWithUuid();
        u.setMfaSecret("enc:v1:stored");
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));
        when(secretCipher.open("enc:v1:stored")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(eq("PLAINSECRET"), anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(recoveryCodes.generate(u.getId()))
                .thenReturn(List.of("AAAA-BBBB", "CCCC-DDDD"));

        mvc.perform(post("/admin/api/mfa/confirm")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.recoveryCodes[0]").value("AAAA-BBBB"));

        verify(recoveryCodes).generate(u.getId());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void verify_totpFails_fallsBackToRecoveryCode() throws Exception {
        AdminUser u = adminUserWithUuid();
        u.setMfaSecret("enc:v1:stored");
        u.setMfaEnabled(true);
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));
        when(secretCipher.open("enc:v1:stored")).thenReturn("PLAINSECRET");
        // TOTP fails for any code → controller must try recovery consume.
        when(totp.verifyAt(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        when(recoveryCodes.consume(eq(u.getId()), anyString())).thenReturn(true);
        when(recoveryCodes.remaining(u.getId())).thenReturn(9L);

        mvc.perform(post("/admin/api/mfa/verify")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"AAAA-BBBB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.usedRecoveryCode").value(true))
                .andExpect(jsonPath("$.remaining").value(9));

        verify(recoveryCodes).consume(eq(u.getId()), anyString());
    }
}
