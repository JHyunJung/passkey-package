package com.crosscert.passkey.admin.system

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.AdminUserDetailsService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.repository.ActivityRepository
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AdminUserTenantRepository
import com.crosscert.passkey.core.repository.ApiKeyRepository
import com.crosscert.passkey.core.repository.ApiKeyScopeRepository
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.SchedulerLeaseRepository
import com.crosscert.passkey.core.repository.SecurityIncidentRepository
import com.crosscert.passkey.core.repository.SecurityPolicyRepository
import com.crosscert.passkey.core.repository.SigningKeyRepository
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.metamodel.Metamodel
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock

@WebMvcTest(
    controllers = [SystemInfoController::class],
    excludeAutoConfiguration = [
        HibernateJpaAutoConfiguration::class,
        DataJpaRepositoriesAutoConfiguration::class,
        DataSourceAutoConfiguration::class,
        DataWebAutoConfiguration::class,
    ],
)
@Import(
    AdminSecurityConfig::class,
    SystemInfoControllerSecurityTest.JpaStubs::class,
)
class SystemInfoControllerSecurityTest {

    /**
     * AdminApplication carries @EnableJpaRepositories which registers
     * jpaSharedEM_entityManagerFactory even in @WebMvcTest slices.
     * We provide a stub EMF with a real (empty) Metamodel so the
     * JPA wiring succeeds without a real DataSource.
     */
    @TestConfiguration
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
    lateinit var service: SystemInfoService

    @MockitoBean
    lateinit var admins: AdminUserRepository

    @MockitoBean
    lateinit var audit: AuditLogService

    @MockitoBean
    lateinit var uds: AdminUserDetailsService

    @MockitoBean
    lateinit var clock: Clock

    @MockitoBean
    lateinit var encoder: PasswordEncoder

    // AdminSecurityConfig wires a DynamicCorsConfigurationSource constructor param.
    @MockitoBean
    lateinit var corsSource: DynamicCorsConfigurationSource

    // AdminSecurityConfig 의 adminFilterChain 이 주입받는 두 빈 — 슬라이스에는 스캔되지 않는다.
    @MockitoBean
    lateinit var securityPolicyService: SecurityPolicyService

    @MockitoBean
    lateinit var tenantBoundary: TenantBoundary

    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
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
    lateinit var adminSignupRequestRepository: AdminSignupRequestRepository

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
    lateinit var securityIncidentRepository: SecurityIncidentRepository

    @MockitoBean
    lateinit var adminUserTenantRepository: AdminUserTenantRepository

    @MockitoBean
    lateinit var credentialAuthEventRepository: CredentialAuthEventRepository

    private companion object {
        fun sampleView(): SystemInfoView =
            // Minimal construction: nested Host null, empty components — only $.success is asserted.
            SystemInfoView(
                "1.0.0", "2026-05-31T09:00+09:00",
                null, null, listOf<SystemInfoView.Component>(),
            )
    }

    @Test
    fun anonymousGetIsUnauthorized() {
        mvc.perform(get("/admin/api/system/info")).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun rpAdminGetIsForbidden() {
        mvc.perform(get("/admin/api/system/info"))
            .andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun platformOperatorCanGet() {
        whenever(service.get()).thenReturn(sampleView())
        mvc.perform(get("/admin/api/system/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
    }
}
