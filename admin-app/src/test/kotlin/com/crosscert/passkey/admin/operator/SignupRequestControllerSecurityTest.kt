package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.AdminUserDetailsService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.repository.ActivityRepository
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.ApiKeyRepository
import com.crosscert.passkey.core.repository.ApiKeyScopeRepository
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.SchedulerLeaseRepository
import com.crosscert.passkey.core.repository.SecurityPolicyRepository
import com.crosscert.passkey.core.repository.SigningKeyRepository
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.metamodel.Metamodel
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.util.UUID

@WebMvcTest(
    controllers = [SignupRequestController::class],
    excludeAutoConfiguration = [
        HibernateJpaAutoConfiguration::class,
        DataJpaRepositoriesAutoConfiguration::class,
        DataSourceAutoConfiguration::class,
        DataWebAutoConfiguration::class,
    ],
)
@Import(
    AdminSecurityConfig::class,
    SignupRequestControllerSecurityTest.JpaStubs::class,
)
class SignupRequestControllerSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(SignupRequestController::class)
    class JpaStubs {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory {
            val metamodel = mock<Metamodel>()
            whenever(metamodel.entities).thenReturn(setOf())
            whenever(metamodel.managedTypes).thenReturn(setOf())
            whenever(metamodel.embeddables).thenReturn(setOf())
            val emf = mock<EntityManagerFactory>()
            whenever(emf.metamodel).thenReturn(metamodel)
            return emf
        }
    }

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var service: SignupRequestService

    @MockitoBean
    lateinit var users: AdminUserRepository

    @MockitoBean
    lateinit var audit: AuditLogService

    @MockitoBean
    lateinit var uds: AdminUserDetailsService

    @MockitoBean
    lateinit var clock: Clock

    @MockitoBean
    lateinit var encoder: PasswordEncoder

    @MockitoBean
    lateinit var corsSource: DynamicCorsConfigurationSource

    @MockitoBean
    lateinit var tenantRepository: TenantRepository

    @MockitoBean
    lateinit var auditLogRepository: AuditLogRepository

    @MockitoBean
    lateinit var ceremonyEventRepository: CeremonyEventRepository

    @MockitoBean
    lateinit var apiKeyRepository: ApiKeyRepository

    @MockitoBean
    lateinit var apiKeyScopeRepository: ApiKeyScopeRepository

    @MockitoBean
    lateinit var credentialRepository: CredentialRepository

    @MockitoBean
    lateinit var signingKeyRepository: SigningKeyRepository

    @MockitoBean
    lateinit var schedulerLeaseRepository: SchedulerLeaseRepository

    @MockitoBean
    lateinit var activityRepository: ActivityRepository

    @MockitoBean
    lateinit var adminPasswordResetTokenRepository: AdminPasswordResetTokenRepository

    @MockitoBean
    lateinit var adminUserRecoveryCodeRepository: AdminUserRecoveryCodeRepository

    @MockitoBean
    lateinit var tenantAaguidPolicyRepository: TenantAaguidPolicyRepository

    @MockitoBean
    lateinit var securityPolicyRepository: SecurityPolicyRepository

    @MockitoBean
    lateinit var tenantWebauthnSnapshotRepository: TenantWebauthnSnapshotRepository

    @MockitoBean
    lateinit var securityPolicyService: SecurityPolicyService

    @MockitoBean
    lateinit var tenantBoundary: TenantBoundary

    private companion object {
        fun `as`(role: String): Authentication {
            val principal = AdminUserDetails(
                UUID.randomUUID(), "who@example.com", "x",
                role, setOf(), true, null, Clock.systemUTC(),
            )
            return UsernamePasswordAuthenticationToken(principal, "x", principal.authorities)
        }

        const val VALID_BODY =
            "{\"email\":\"new@example.com\",\"password\":\"password-12chars\",\"reason\":\"RP 담당\"}"
    }

    // ── 공개 POST ──────────────────────────────────────────────────────────

    @Test
    fun publicRequest_anonymousWithoutCsrf_isAccepted202() {
        mvc.perform(
            post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.accepted").value(true))
        verify(service).request(anyOrNull())
    }

    @Test
    fun publicRequest_shortPassword_is400_andServiceNotCalled() {
        mvc.perform(
            post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@example.com\",\"password\":\"short\"}"),
        )
            .andExpect(status().isBadRequest())
        verify(service, never()).request(anyOrNull())
    }

    @Test
    fun publicRequest_invalidEmail_is400() {
        mvc.perform(
            post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"password-12chars\"}"),
        )
            .andExpect(status().isBadRequest())
    }

    // ── 관리 GET ───────────────────────────────────────────────────────────

    @Test
    fun list_anonymous_is401() {
        mvc.perform(get("/admin/api/signup-requests")).andExpect(status().isUnauthorized())
    }

    @Test
    fun list_rpAdmin_is403() {
        mvc.perform(get("/admin/api/signup-requests").with(authentication(`as`("RP_ADMIN"))))
            .andExpect(status().isForbidden())
    }

    @Test
    fun list_platformOperator_is200() {
        whenever(service.list()).thenReturn(listOf())
        mvc.perform(get("/admin/api/signup-requests").with(authentication(`as`("PLATFORM_OPERATOR"))))
            .andExpect(status().isOk())
    }

    // ── approve / reject ───────────────────────────────────────────────────

    @Test
    fun approve_anonymous_is401() {
        mvc.perform(
            post("/admin/api/signup-requests/" + UUID.randomUUID() + "/approve")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"),
        )
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun approve_rpAdmin_is403() {
        mvc.perform(
            post("/admin/api/signup-requests/" + UUID.randomUUID() + "/approve")
                .with(authentication(`as`("RP_ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"),
        )
            .andExpect(status().isForbidden())
        verify(service, never()).approve(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun approve_platformOperator_delegates() {
        val id = UUID.randomUUID()
        whenever(service.approve(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(
            AdminUserDto.View(
                UUID.randomUUID(), "new@example.com", "PLATFORM_OPERATOR", "ACTIVE",
                listOf(), null, null, null, "who@example.com", false,
            ),
        )
        mvc.perform(
            post("/admin/api/signup-requests/$id/approve")
                .with(authentication(`as`("PLATFORM_OPERATOR"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("new@example.com"))
    }

    @Test
    fun reject_platformOperator_is204() {
        mvc.perform(
            post("/admin/api/signup-requests/" + UUID.randomUUID() + "/reject")
                .with(authentication(`as`("PLATFORM_OPERATOR"))).with(csrf()),
        )
            .andExpect(status().isNoContent())
        verify(service).reject(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun reject_rpAdmin_is403() {
        mvc.perform(
            post("/admin/api/signup-requests/" + UUID.randomUUID() + "/reject")
                .with(authentication(`as`("RP_ADMIN"))).with(csrf()),
        )
            .andExpect(status().isForbidden())
    }
}
