package com.crosscert.passkey.admin.credential

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.AdminUserDetailsService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.api.PageView
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.util.UUID

/**
 * F12 회귀 채널 — credential 조회 GET 의 @PreAuthorize 컨트롤러 게이트 검증.
 *
 * 형제 컨트롤러(ApiKeyAdminControllerSecurityTest 등)와 동일한 @WebMvcTest 슬라이스 +
 * @WithMockUser(roles=...) 패턴. Oracle/Redis Testcontainers 없이 메서드 보안만 검증한다.
 *
 * 시나리오:
 *   - 미인증 GET → 401 (SecurityFilterChain 의 .authenticated())
 *   - 인증됐지만 PLATFORM_OPERATOR/RP_ADMIN 이 아닌 role → 403 (컨트롤러 진입 전 @PreAuthorize 게이트)
 *   - PLATFORM_OPERATOR / RP_ADMIN → 200 (동작 불변)
 *
 * list(GET) 와 authEvents(GET /{credentialId}/auth-events) 두 엔드포인트를 각각 검증.
 */
@WebMvcTest(
    controllers = [CredentialAdminController::class],
    excludeAutoConfiguration = [
        HibernateJpaAutoConfiguration::class,
        DataJpaRepositoriesAutoConfiguration::class,
        DataSourceAutoConfiguration::class,
        DataWebAutoConfiguration::class,
    ],
)
@Import(AdminSecurityConfig::class, CredentialAdminControllerSecurityTest.JpaStubs::class)
class CredentialAdminControllerSecurityTest {

    /**
     * AdminApplication 의 @EnableJpaRepositories 가 슬라이스에서도
     * jpaSharedEM_entityManagerFactory 를 요구하므로, 빈(empty) Metamodel 을 가진
     * EMF 스텁을 제공해 실제 DataSource 없이 JPA 배선이 성공하게 한다.
     */
    @TestConfiguration
    class JpaStubs {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory {
            val metamodel: Metamodel = mock()
            whenever(metamodel.entities).thenReturn(setOf())
            whenever(metamodel.managedTypes).thenReturn(setOf())
            whenever(metamodel.embeddables).thenReturn(setOf())

            val emf: EntityManagerFactory = mock()
            whenever(emf.metamodel).thenReturn(metamodel)
            return emf
        }
    }

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var service: CredentialAdminService

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

    @MockitoBean
    lateinit var corsSource: DynamicCorsConfigurationSource

    // AdminSecurityConfig 의 핸들러 빈들이 요구하는 협력자 — 슬라이스에서 컴포넌트 스캔
    // 대상이 아니므로 명시적으로 mock 제공(컨텍스트 로드용).
    @MockitoBean
    lateinit var securityPolicyService: SecurityPolicyService

    @MockitoBean
    lateinit var tenantBoundary: TenantBoundary

    // @EnableJpaRepositories 가 실제 Spring Data 리포지토리를 배선하지 못하게 막음
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
    lateinit var adminUserTenantRepository: AdminUserTenantRepository

    @MockitoBean
    lateinit var credentialAuthEventRepository: CredentialAuthEventRepository

    @MockitoBean
    lateinit var securityIncidentRepository: SecurityIncidentRepository

    /**
     * AdminSecurityConfig 가 설치하는 TenantContextAdminFilter 는 컨트롤러 진입 전
     * tenantBoundary.currentTenantScope() 를 호출해 scope()?.let(...) 한다.
     * unstubbed mock 의 Mockito 기본값(ReturnsEmptyValues)이 이미 null(= scope 없음)이라
     * 문제는 없지만, 그 암묵적 기본값에 기대지 않도록 의도를
     * 명시 고정한다(테스트 견고성 — role-gate 검증이 필터 NPE 로 오염되지 않게).
     */
    @BeforeEach
    fun stubTenantBoundaryScope() {
        whenever(tenantBoundary.currentTenantScope()).thenReturn(null)
    }

    // ---------------------------------------------------------------
    // list (GET /admin/api/tenants/{tenantId}/credentials)
    // ---------------------------------------------------------------

    @Test
    fun list_anonymous_unauthorized() {
        mvc.perform(get(LIST_PATH)).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["SOMETHING_ELSE"])
    fun list_withoutRequiredRole_forbiddenAtControllerGate() {
        mvc.perform(get(LIST_PATH)).andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun list_platformOperator_ok() {
        whenever(service.list(any<UUID>(), anyInt(), anyInt(), anyOrNull()))
            .thenReturn(PageView(listOf(), 0, 50, 0L, false))
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun list_rpAdmin_ok() {
        whenever(service.list(any<UUID>(), anyInt(), anyInt(), anyOrNull()))
            .thenReturn(PageView(listOf(), 0, 50, 0L, false))
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
    }

    // ---------------------------------------------------------------
    // authEvents (GET .../{credentialId}/auth-events)
    // ---------------------------------------------------------------

    @Test
    fun authEvents_anonymous_unauthorized() {
        mvc.perform(get(AUTH_EVENTS_PATH)).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["SOMETHING_ELSE"])
    fun authEvents_withoutRequiredRole_forbiddenAtControllerGate() {
        mvc.perform(get(AUTH_EVENTS_PATH)).andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun authEvents_platformOperator_ok() {
        whenever(service.listAuthEvents(any<UUID>(), anyString(), anyInt(), anyInt()))
            .thenReturn(PageView(listOf(), 0, 50, 0L, false))
        mvc.perform(get(AUTH_EVENTS_PATH)).andExpect(status().isOk())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun authEvents_rpAdmin_ok() {
        whenever(service.listAuthEvents(any<UUID>(), anyString(), anyInt(), anyInt()))
            .thenReturn(PageView(listOf(), 0, 50, 0L, false))
        mvc.perform(get(AUTH_EVENTS_PATH)).andExpect(status().isOk())
    }

    private companion object {
        val TENANT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        const val CREDENTIAL_ID = "AAAAAAAAAAAAAAAAAAAAAA"

        val LIST_PATH = "/admin/api/tenants/$TENANT_ID/credentials"
        val AUTH_EVENTS_PATH = "$LIST_PATH/$CREDENTIAL_ID/auth-events"
    }
}
