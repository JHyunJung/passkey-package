package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.api.GlobalExceptionHandler
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * @WebMvcTest slice for [MfaController], mirroring the
 * `*ControllerSecurityTest` pattern (nested `@SpringBootConfiguration`
 * to short-circuit `@WebMvcTest`'s upward search for `AdminApplication`
 * and its JPA wiring; `AdminSecurityConfig` + `GlobalExceptionHandler`
 * imported; collaborators mocked).
 *
 * Uses the REAL (lightweight) [TotpService] so verify can be exercised
 * against a genuine RFC 6238 code, plus a fixed [Clock] so the code is
 * deterministic. Only [AdminUserRepository] is mocked.
 *
 * That these endpoints are reachable at all under `AdminSecurityConfig`
 * (which now wires `MfaPendingFilter`) also confirms the filter passes
 * `/admin/api/mfa/verify` for pending sessions (enroll/confirm/disable
 * are blocked).
 */
@WebMvcTest(controllers = [MfaController::class])
@Import(AdminSecurityConfig::class, GlobalExceptionHandler::class)
class MfaControllerSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MfaController::class)
    class SliceConfig {
        // Real TotpService (no external deps) so verify uses a genuine RFC 6238 code.
        @Bean
        fun totpService(): TotpService = TotpService()

        // Fixed clock so the generated code matches the verified window deterministically.
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC)
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var totp: TotpService

    // New collaborators introduced by the recovery-code / secret-seal wiring.
    // secretCipher.open(...) is stubbed as identity in @BeforeEach so these tests
    // (which store PLAINTEXT secrets) keep exercising validCode exactly as before.
    @MockitoBean
    lateinit var secretCipher: MfaSecretCipher

    @MockitoBean
    lateinit var recoveryCodes: RecoveryCodeService

    @MockitoBean
    lateinit var admins: AdminUserRepository

    // AdminSecurityConfig collaborators that are not otherwise supplied by the slice.
    @MockitoBean
    lateinit var audit: AuditLogService

    @MockitoBean
    lateinit var uds: AdminUserDetailsService

    @MockitoBean
    lateinit var encoder: PasswordEncoder

    // AdminSecurityConfig 협력 빈(adminLoginSuccessHandler 의 SecurityPolicyService, adminFilterChain 의 TenantBoundary).
    // 이 클래스는 자체 SliceConfig(실제 TotpService·고정 Clock)를 쓰므로 AdminWebSliceTest 묶음(Clock mock 포함) 대신 개별로 둔다.
    @MockitoBean
    lateinit var securityPolicyService: SecurityPolicyService

    @MockitoBean
    lateinit var tenantBoundary: TenantBoundary

    @MockitoBean
    lateinit var corsSource: DynamicCorsConfigurationSource

    @BeforeEach
    fun stubCipherIdentity() {
        // These tests store PLAINTEXT secrets, so seal/open must pass through
        // unchanged for validCode (which now opens the stored value) to keep
        // verifying against the same Base32 secret the test generated.
        whenever(secretCipher.seal(anyString())).thenAnswer { inv -> inv.getArgument<String>(0) }
        whenever(secretCipher.open(anyString())).thenAnswer { inv -> inv.getArgument<String>(0) }
    }

    // ---- verify ---------------------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun verify_validCode_is200_andClearsMfaPending() {
        val secret = totp.newSecretBase32()
        val u = adminUserWithUuid()
        u.mfaSecret = secret
        u.isMfaEnabled = true
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        val validCode = totp.generate(secret, FIXED_MILLIS)

        // Session starts MFA-pending; a successful verify must clear the flag.
        val session = MockHttpSession()
        session.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE)

        mvc.perform(
            post("/admin/api/mfa/verify")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"$validCode\"}"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true))

        assertThat(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).isNull()
        // Security invariant: a valid TOTP must NEVER consume a recovery code.
        verify(recoveryCodes, never()).consume(anyOrNull(), anyOrNull())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun verify_wrongCode_is401_invalidCode() {
        val secret = totp.newSecretBase32()
        val u = adminUserWithUuid()
        u.mfaSecret = secret
        u.isMfaEnabled = true
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        val session = MockHttpSession()
        session.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE)

        mvc.perform(
            post("/admin/api/mfa/verify")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"000000\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_code"))

        // Flag must remain set after a failed verify.
        assertThat(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).isEqualTo(java.lang.Boolean.TRUE)
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun verify_noSecretEnrolled_is401_invalidCode() {
        val u = adminUserWithUuid() // mfaSecret == null
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        mvc.perform(
            post("/admin/api/mfa/verify")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"123456\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_code"))
    }

    // ---- enroll ---------------------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun enroll_is200_returnsSecretAndUri_andDoesNotEnableMfaYet() {
        val u = adminUserWithUuid()
        assertThat(u.isMfaEnabled).isFalse() // precondition
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        mvc.perform(
            post("/admin/api/mfa/enroll")
                .with(csrf()),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.secret").isNotEmpty())
            .andExpect(jsonPath("$.otpauthUri").value(startsWith("otpauth://totp/")))
            // Spaces in the issuer/label must be percent-encoded (%20), not
            // '+' as application/x-www-form-urlencoded would emit, so QR apps
            // parse "Passkey Admin" correctly.
            .andExpect(jsonPath("$.otpauthUri").value(containsString("Passkey%20Admin")))
            .andExpect(jsonPath("$.otpauthUri").value(not(containsString("Passkey+Admin"))))

        val saved = argumentCaptor<AdminUser>()
        verify(admins).save(saved.capture())
        // Enroll stores the secret but leaves MFA DISABLED until confirm() succeeds,
        // so an abandoned re-enroll cannot lock the operator out on next login.
        assertThat(saved.lastValue.isMfaEnabled).isFalse()
        assertThat(saved.lastValue.mfaSecret).isNotBlank()
        // Returned secret must round-trip through Base32 to >= 20 bytes (160-bit).
        assertThat(totp.decodeSecretForTest(saved.lastValue.mfaSecret).size)
            .isGreaterThanOrEqualTo(20)
    }

    @Test
    @WithMockUser(username = "ghost@example.com", roles = ["PLATFORM_OPERATOR"])
    fun enroll_missingUserRow_is401_unauthorized() {
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.empty())

        mvc.perform(
            post("/admin/api/mfa/enroll")
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("unauthorized"))
    }

    // ---- confirm --------------------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun confirm_validCode_is200_andEnablesMfa() {
        val secret = totp.newSecretBase32()
        val u = adminUserWithUuid()
        u.mfaSecret = secret // enrolled but not yet enabled
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        val validCode = totp.generate(secret, FIXED_MILLIS)

        mvc.perform(
            post("/admin/api/mfa/confirm")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"$validCode\"}"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.confirmed").value(true))

        val saved = argumentCaptor<AdminUser>()
        verify(admins).save(saved.capture())
        assertThat(saved.lastValue.isMfaEnabled).isTrue()
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun confirm_wrongCode_is401_andDoesNotEnableMfa() {
        val secret = totp.newSecretBase32()
        val u = adminUserWithUuid()
        u.mfaSecret = secret
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        mvc.perform(
            post("/admin/api/mfa/confirm")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"000000\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_code"))

        // A failed confirm must not enable MFA nor persist the row.
        assertThat(u.isMfaEnabled).isFalse()
        verify(admins, never()).save(anyOrNull())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun confirm_noSecretEnrolled_is401_andDoesNotSave() {
        val u = adminUserWithUuid() // mfaSecret == null (never enrolled)
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        mvc.perform(
            post("/admin/api/mfa/confirm")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"123456\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_code"))

        assertThat(u.isMfaEnabled).isFalse()
        verify(admins, never()).save(anyOrNull())
    }

    // ---- disable --------------------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun disable_validCode_is200_andDisablesMfa_andClearsSecret() {
        val secret = totp.newSecretBase32()
        val u = adminUserWithUuid()
        u.mfaSecret = secret
        u.isMfaEnabled = true
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        val validCode = totp.generate(secret, FIXED_MILLIS)

        mvc.perform(
            post("/admin/api/mfa/disable")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"$validCode\"}"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disabled").value(true))

        val saved = argumentCaptor<AdminUser>()
        verify(admins).save(saved.capture())
        assertThat(saved.lastValue.isMfaEnabled).isFalse()
        assertThat(saved.lastValue.mfaSecret).isNull()
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun disable_wrongCode_is401() {
        val secret = totp.newSecretBase32()
        val u = adminUserWithUuid()
        u.mfaSecret = secret
        u.isMfaEnabled = true
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        mvc.perform(
            post("/admin/api/mfa/disable")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"000000\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_code"))
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun disable_noSecretEnrolled_is401_andDoesNotSave() {
        val u = adminUserWithUuid() // mfaSecret == null (never enrolled)
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))

        mvc.perform(
            post("/admin/api/mfa/disable")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"123456\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_code"))

        verify(admins, never()).save(anyOrNull())
    }

    companion object {
        /** Fixed instant used by the test Clock; any value works for TOTP determinism. */
        private const val FIXED_MILLIS = 1_700_000_000_000L

        private fun adminUserWithUuid(): AdminUser {
            val u = AdminUser("alice@example.com", "x", "ADMIN")
            try {
                val f = AdminUser::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                f.set(u, UUID.randomUUID())
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
            return u
        }
    }
}
