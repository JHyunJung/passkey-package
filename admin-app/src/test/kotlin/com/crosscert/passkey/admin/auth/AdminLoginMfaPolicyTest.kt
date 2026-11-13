package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.config.MeController
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource
import com.crosscert.passkey.admin.policy.SecurityPolicyDto
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.userdetails.cache.NullUserCache
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Optional
import java.util.UUID

/**
 * F01 Task 1 — 로그인 성공 시 MFA 필수 정책 판정(Spec §4.1).
 * 실제 formLogin 체인을 통과시킨다(AdminLoginCredentialErasureTest 와 같은 슬라이스 패턴).
 */
@WebMvcTest(controllers = [MeController::class])
@Import(AdminSecurityConfig::class)
class AdminLoginMfaPolicyTest {

    companion object {
        private const val EMAIL = "rp@example.com"
        private const val PASSWORD = "correct-horse-battery-staple"
        private val HASH: String = BCryptPasswordEncoder(4).encode(PASSWORD)!!
        private val ADMIN_ID: UUID = UUID.fromString("0f000000-0000-0000-0000-000000000001")
        private val TENANT_ID: UUID = UUID.fromString("0a000000-0000-0000-0000-000000000001")

        private fun freshDetails(): AdminUserDetails =
            AdminUserDetails(ADMIN_ID, EMAIL, HASH, "RP_ADMIN", setOf(TENANT_ID), true, null, Clock.systemUTC())

        private fun adminUser(mfaEnabled: Boolean): AdminUser {
            val u = AdminUser(EMAIL, HASH, "RP_ADMIN")
            u.isMfaEnabled = mfaEnabled
            return u
        }

        private fun view(mfaRequired: Boolean, idle: Int = 30) =
            SecurityPolicyDto.View(idle, mfaRequired, listOf(), null, null)

        private fun session(r: MvcResult): MockHttpSession {
            val s = r.request.getSession(false) as MockHttpSession?
            assertThat(s).isNotNull()
            return s!!
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MeController::class)
    class SliceConfig {
        @Bean
        fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(4)

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var uds: AdminUserDetailsService

    @MockitoBean
    lateinit var users: AdminUserRepository

    @MockitoBean
    lateinit var audit: AuditLogService

    @MockitoBean
    lateinit var corsSource: DynamicCorsConfigurationSource

    @MockitoBean
    lateinit var securityPolicyService: SecurityPolicyService

    @MockitoBean
    lateinit var tenantBoundary: TenantBoundary

    private fun stubUser(mfaEnabled: Boolean) {
        whenever(uds.loadUserByUsername(EMAIL)).thenAnswer { freshDetails() }
        whenever(users.findByEmail(EMAIL)).thenAnswer { Optional.of(adminUser(mfaEnabled)) }
    }

    private fun jsonLogin(): MvcResult =
        mvc.perform(
            post("/admin/login").param("email", EMAIL).param("password", PASSWORD).with(csrf().asHeader()),
        ).andReturn()

    private fun pageLogin(): MvcResult =
        mvc.perform(
            post("/admin/login").param("email", EMAIL).param("password", PASSWORD).with(csrf()),
        ).andReturn()

    private fun assertState(r: MvcResult, pending: Boolean, enroll: Boolean) {
        val s = session(r)
        assertThat(s.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))
            .isEqualTo(if (pending) java.lang.Boolean.TRUE else null)
        assertThat(s.getAttribute(MfaSessionState.ENROLL_REQUIRED_ATTR))
            .isEqualTo(if (enroll) java.lang.Boolean.TRUE else null)
    }

    @Test
    fun mfaEnabled_pendingRegardlessOfPolicy() {
        stubUser(true)
        whenever(securityPolicyService.get()).thenReturn(view(true))
        val r = jsonLogin()
        assertThat(r.response.status).isEqualTo(200)
        assertState(r, pending = true, enroll = false)
        mvc.perform(post("/admin/login").param("email", EMAIL).param("password", PASSWORD).with(csrf().asHeader()))
            .andExpect(jsonPath("$.mfaRequired").value(true))
            .andExpect(jsonPath("$.mfaEnrollRequired").value(false))
    }

    @Test
    fun notEnrolled_policyOn_enrollRequired() {
        stubUser(false)
        whenever(securityPolicyService.get()).thenReturn(view(true))
        val r = jsonLogin()
        assertThat(r.response.status).isEqualTo(200)
        assertState(r, pending = false, enroll = true)
        mvc.perform(post("/admin/login").param("email", EMAIL).param("password", PASSWORD).with(csrf().asHeader()))
            .andExpect(jsonPath("$.mfaRequired").value(false))
            .andExpect(jsonPath("$.mfaEnrollRequired").value(true))

        val p = pageLogin()
        assertThat(p.response.redirectedUrl).isEqualTo("/admin/login")
        assertState(p, pending = false, enroll = true)
    }

    @Test
    fun notEnrolled_policyOff_unchanged() {
        stubUser(false)
        whenever(securityPolicyService.get()).thenReturn(view(false))
        val r = jsonLogin()
        assertState(r, pending = false, enroll = false)
        val body = r.response.contentAsString
        val keys = com.fasterxml.jackson.databind.ObjectMapper().readTree(body).fieldNames().asSequence().toSet()
        assertThat(keys).containsExactlyInAnyOrder("email", "role", "mfaRequired", "mfaEnrollRequired")
        assertThat(pageLogin().response.redirectedUrl).isEqualTo("/admin")
    }

    @Test
    fun policyMissingRow_failsClosed() {
        stubUser(false)
        whenever(securityPolicyService.get()).thenThrow(IllegalStateException("security_policy singleton row missing"))
        val r = jsonLogin()
        assertThat(r.response.status).isEqualTo(200)
        assertState(r, pending = false, enroll = true)
        assertThat(session(r).maxInactiveInterval).isEqualTo(30 * 60)
    }

    @Test
    fun policyCorruptCors_failsClosed() {
        stubUser(false)
        whenever(securityPolicyService.get()).thenThrow(IllegalStateException("deserialize cors_allowlist failed"))
        val r = jsonLogin()
        assertState(r, pending = false, enroll = true)
        assertThat(session(r).maxInactiveInterval).isEqualTo(30 * 60)
    }

    @Test
    fun policyOn_idleTimeoutStillApplied() {
        stubUser(false)
        whenever(securityPolicyService.get()).thenReturn(view(true, 45))
        val r = jsonLogin()
        assertThat(session(r).maxInactiveInterval).isEqualTo(2700)
    }
}
