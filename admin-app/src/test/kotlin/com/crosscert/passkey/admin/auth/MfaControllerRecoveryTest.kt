package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.api.GlobalExceptionHandler
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
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
import java.util.OptionalLong
import java.util.UUID

/**
 * @WebMvcTest slice for [MfaController] covering the two NEW behaviors
 * wired in this task: recovery-code issuance on `confirm()` and the
 * TOTP-failure → recovery-code fallback on `verify()`.
 *
 * Mirrors [MfaControllerSecurityTest]'s slice setup exactly (nested
 * `@SpringBootConfiguration`, real [TotpService] + fixed [Clock],
 * same security imports + MockBeans) so the security/CSRF/principal wiring is
 * identical. Here [TotpService] is replaced by a mock via `@MockitoBean`
 * to deterministically force TOTP success/failure, and [MfaSecretCipher] /
 * [RecoveryCodeService] are mocked.
 */
@WebMvcTest(controllers = [MfaController::class])
@Import(AdminSecurityConfig::class, GlobalExceptionHandler::class)
class MfaControllerRecoveryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MfaController::class)
    class SliceConfig {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC)
    }

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var totp: TotpService

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

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun confirm_validCode_returnsRecoveryCodesInBody() {
        val u = adminUserWithUuid()
        u.mfaSecret = "enc:v1:stored"
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))
        whenever(secretCipher.open("enc:v1:stored")).thenReturn("PLAINSECRET")
        // MfaController.validCode 는 F02(재사용 방지) 이후 matchedCounter 로 매칭된 time-step 을 받는다.
        whenever(totp.matchedCounter(eq("PLAINSECRET"), anyString(), anyLong()))
            .thenReturn(OptionalLong.of(56_666_666L))
        whenever(recoveryCodes.generate(u.id))
            .thenReturn(listOf("AAAA-BBBB", "CCCC-DDDD"))

        mvc.perform(
            post("/admin/api/mfa/confirm")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"123456\"}"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.confirmed").value(true))
            .andExpect(jsonPath("$.recoveryCodes[0]").value("AAAA-BBBB"))

        verify(recoveryCodes).generate(u.id)
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun verify_totpFails_fallsBackToRecoveryCode() {
        val u = adminUserWithUuid()
        u.mfaSecret = "enc:v1:stored"
        u.isMfaEnabled = true
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))
        whenever(secretCipher.open("enc:v1:stored")).thenReturn("PLAINSECRET")
        // TOTP fails for any code → controller must try recovery consume.
        whenever(totp.matchedCounter(anyString(), anyString(), anyLong()))
            .thenReturn(OptionalLong.empty())
        whenever(recoveryCodes.consume(eq(u.id), anyString())).thenReturn(true)
        whenever(recoveryCodes.remaining(u.id)).thenReturn(9L)

        mvc.perform(
            post("/admin/api/mfa/verify")
                .with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"AAAA-BBBB\"}"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true))
            .andExpect(jsonPath("$.usedRecoveryCode").value(true))
            .andExpect(jsonPath("$.remaining").value(9))

        verify(recoveryCodes).consume(eq(u.id), anyString())
    }

    companion object {
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
