package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MfaController}, mirroring the
 * {@code *ControllerSecurityTest} pattern (nested {@code @SpringBootConfiguration}
 * to short-circuit {@code @WebMvcTest}'s upward search for {@code AdminApplication}
 * and its JPA wiring; {@code AdminSecurityConfig} + {@code GlobalExceptionHandler}
 * imported; collaborators mocked).
 *
 * <p>Uses the REAL (lightweight) {@link TotpService} so verify can be exercised
 * against a genuine RFC 6238 code, plus a fixed {@link Clock} so the code is
 * deterministic. Only {@link AdminUserRepository} is mocked.
 *
 * <p>That these endpoints are reachable at all under {@code AdminSecurityConfig}
 * (which now wires {@code MfaPendingFilter}) also confirms the filter does not
 * block {@code /admin/api/mfa/**}.
 */
@WebMvcTest(controllers = MfaController.class)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    com.crosscert.passkey.core.api.GlobalExceptionHandler.class
})
class MfaControllerSecurityTest {

    /** Fixed instant used by the test Clock; any value works for TOTP determinism. */
    private static final long FIXED_MILLIS = 1_700_000_000_000L;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MfaController.class)
    static class SliceConfig {
        // Real TotpService (no external deps) so verify uses a genuine RFC 6238 code.
        @Bean TotpService totpService() { return new TotpService(); }
        // Fixed clock so the generated code matches the verified window deterministically.
        @Bean Clock clock() { return Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC); }
    }

    @Autowired MockMvc mvc;
    @Autowired TotpService totp;

    // New collaborators introduced by the recovery-code / secret-seal wiring.
    // secretCipher.open(...) is stubbed as identity in @BeforeEach so these tests
    // (which store PLAINTEXT secrets) keep exercising validCode exactly as before.
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

    @BeforeEach
    void stubCipherIdentity() {
        // These tests store PLAINTEXT secrets, so seal/open must pass through
        // unchanged for validCode (which now opens the stored value) to keep
        // verifying against the same Base32 secret the test generated.
        when(secretCipher.seal(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(secretCipher.open(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- verify ---------------------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void verify_validCode_is200_andClearsMfaPending() throws Exception {
        String secret = totp.newSecretBase32();
        AdminUser u = adminUserWithUuid();
        u.setMfaSecret(secret);
        u.setMfaEnabled(true);
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        String validCode = totp.generate(secret, FIXED_MILLIS);

        // Session starts MFA-pending; a successful verify must clear the flag.
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE);

        mvc.perform(post("/admin/api/mfa/verify")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"" + validCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        assertThat(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).isNull();
        // Security invariant: a valid TOTP must NEVER consume a recovery code.
        verify(recoveryCodes, never()).consume(any(), any());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void verify_wrongCode_is401_invalidCode() throws Exception {
        String secret = totp.newSecretBase32();
        AdminUser u = adminUserWithUuid();
        u.setMfaSecret(secret);
        u.setMfaEnabled(true);
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE);

        mvc.perform(post("/admin/api/mfa/verify")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));

        // Flag must remain set after a failed verify.
        assertThat(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).isEqualTo(Boolean.TRUE);
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void verify_noSecretEnrolled_is401_invalidCode() throws Exception {
        AdminUser u = adminUserWithUuid(); // mfaSecret == null
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        mvc.perform(post("/admin/api/mfa/verify")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));
    }

    // ---- enroll ---------------------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void enroll_is200_returnsSecretAndUri_andDoesNotEnableMfaYet() throws Exception {
        AdminUser u = adminUserWithUuid();
        assertThat(u.isMfaEnabled()).isFalse(); // precondition
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        mvc.perform(post("/admin/api/mfa/enroll")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.otpauthUri").value(startsWith("otpauth://totp/")))
                // Spaces in the issuer/label must be percent-encoded (%20), not
                // '+' as application/x-www-form-urlencoded would emit, so QR apps
                // parse "Passkey Admin" correctly.
                .andExpect(jsonPath("$.otpauthUri").value(containsString("Passkey%20Admin")))
                .andExpect(jsonPath("$.otpauthUri").value(not(containsString("Passkey+Admin"))));

        ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(admins).save(saved.capture());
        // Enroll stores the secret but leaves MFA DISABLED until confirm() succeeds,
        // so an abandoned re-enroll cannot lock the operator out on next login.
        assertThat(saved.getValue().isMfaEnabled()).isFalse();
        assertThat(saved.getValue().getMfaSecret()).isNotBlank();
        // Returned secret must round-trip through Base32 to >= 20 bytes (160-bit).
        assertThat(totp.decodeSecretForTest(saved.getValue().getMfaSecret()).length)
                .isGreaterThanOrEqualTo(20);
    }

    @Test
    @WithMockUser(username = "ghost@example.com", roles = "PLATFORM_OPERATOR")
    void enroll_missingUserRow_is401_unauthorized() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/admin/api/mfa/enroll")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // ---- confirm --------------------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void confirm_validCode_is200_andEnablesMfa() throws Exception {
        String secret = totp.newSecretBase32();
        AdminUser u = adminUserWithUuid();
        u.setMfaSecret(secret); // enrolled but not yet enabled
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        String validCode = totp.generate(secret, FIXED_MILLIS);

        mvc.perform(post("/admin/api/mfa/confirm")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"" + validCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));

        ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(admins).save(saved.capture());
        assertThat(saved.getValue().isMfaEnabled()).isTrue();
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void confirm_wrongCode_is401_andDoesNotEnableMfa() throws Exception {
        String secret = totp.newSecretBase32();
        AdminUser u = adminUserWithUuid();
        u.setMfaSecret(secret);
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        mvc.perform(post("/admin/api/mfa/confirm")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));

        // A failed confirm must not enable MFA nor persist the row.
        assertThat(u.isMfaEnabled()).isFalse();
        verify(admins, never()).save(any());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void confirm_noSecretEnrolled_is401_andDoesNotSave() throws Exception {
        AdminUser u = adminUserWithUuid(); // mfaSecret == null (never enrolled)
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        mvc.perform(post("/admin/api/mfa/confirm")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));

        assertThat(u.isMfaEnabled()).isFalse();
        verify(admins, never()).save(any());
    }

    // ---- disable --------------------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void disable_validCode_is200_andDisablesMfa_andClearsSecret() throws Exception {
        String secret = totp.newSecretBase32();
        AdminUser u = adminUserWithUuid();
        u.setMfaSecret(secret);
        u.setMfaEnabled(true);
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        String validCode = totp.generate(secret, FIXED_MILLIS);

        mvc.perform(post("/admin/api/mfa/disable")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"" + validCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled").value(true));

        ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(admins).save(saved.capture());
        assertThat(saved.getValue().isMfaEnabled()).isFalse();
        assertThat(saved.getValue().getMfaSecret()).isNull();
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void disable_wrongCode_is401() throws Exception {
        String secret = totp.newSecretBase32();
        AdminUser u = adminUserWithUuid();
        u.setMfaSecret(secret);
        u.setMfaEnabled(true);
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        mvc.perform(post("/admin/api/mfa/disable")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void disable_noSecretEnrolled_is401_andDoesNotSave() throws Exception {
        AdminUser u = adminUserWithUuid(); // mfaSecret == null (never enrolled)
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));

        mvc.perform(post("/admin/api/mfa/disable")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));

        verify(admins, never()).save(any());
    }
}
