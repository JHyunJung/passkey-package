package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.core.api.PageView;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    controllers = CredentialAdminController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    CredentialAdminControllerSecurityTest.JpaStubs.class
})
class CredentialAdminControllerSecurityTest {

    /**
     * AdminApplication 의 @EnableJpaRepositories 가 슬라이스에서도
     * jpaSharedEM_entityManagerFactory 를 요구하므로, 빈(empty) Metamodel 을 가진
     * EMF 스텁을 제공해 실제 DataSource 없이 JPA 배선이 성공하게 한다.
     */
    @TestConfiguration
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            Metamodel metamodel = mock(Metamodel.class);
            org.mockito.Mockito.when(metamodel.getEntities()).thenReturn(Set.of());
            org.mockito.Mockito.when(metamodel.getManagedTypes()).thenReturn(Set.of());
            org.mockito.Mockito.when(metamodel.getEmbeddables()).thenReturn(Set.of());

            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            org.mockito.Mockito.when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean CredentialAdminService service;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    // AdminSecurityConfig 의 핸들러 빈들이 요구하는 협력자 — 슬라이스에서 컴포넌트 스캔
    // 대상이 아니므로 명시적으로 mock 제공(컨텍스트 로드용).
    @MockBean com.crosscert.passkey.admin.policy.SecurityPolicyService securityPolicyService;
    @MockBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;
    // @EnableJpaRepositories 가 실제 Spring Data 리포지토리를 배선하지 못하게 막음
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyScopeRepository apiKeyScopeRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserInvitationRepository invitationRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository adminPasswordResetTokenRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository adminUserRecoveryCodeRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserTenantRepository adminUserTenantRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialAuthEventRepository credentialAuthEventRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityIncidentRepository securityIncidentRepository;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String CREDENTIAL_ID = "AAAAAAAAAAAAAAAAAAAAAA";

    private static final String LIST_PATH = "/admin/api/tenants/" + TENANT_ID + "/credentials";
    private static final String AUTH_EVENTS_PATH = LIST_PATH + "/" + CREDENTIAL_ID + "/auth-events";

    /**
     * AdminSecurityConfig 가 설치하는 TenantContextAdminFilter 는 컨트롤러 진입 전
     * tenantBoundary.currentTenantScope() 를 호출해 scope().ifPresent(...) 한다.
     * Optional 반환이라 unstubbed mock 의 Mockito 기본값(ReturnsEmptyValues)이 이미
     * Optional.empty() 라 NPE 는 안 나지만, 그 암묵적 기본값에 기대지 않도록 의도를
     * 명시 고정한다(테스트 견고성 — role-gate 검증이 필터 NPE 로 오염되지 않게).
     */
    @BeforeEach
    void stubTenantBoundaryScope() {
        org.mockito.Mockito.when(tenantBoundary.currentTenantScope())
                .thenReturn(java.util.Optional.empty());
    }

    // ---------------------------------------------------------------
    // list (GET /admin/api/tenants/{tenantId}/credentials)
    // ---------------------------------------------------------------

    @Test
    void list_anonymous_unauthorized() throws Exception {
        mvc.perform(get(LIST_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "SOMETHING_ELSE")
    void list_withoutRequiredRole_forbiddenAtControllerGate() throws Exception {
        mvc.perform(get(LIST_PATH)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
    void list_platformOperator_ok() throws Exception {
        org.mockito.Mockito.when(service.list(any(UUID.class), anyInt(), anyInt(), any()))
                .thenReturn(new PageView<>(List.of(), 0, 50, 0L, false));
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void list_rpAdmin_ok() throws Exception {
        org.mockito.Mockito.when(service.list(any(UUID.class), anyInt(), anyInt(), any()))
                .thenReturn(new PageView<>(List.of(), 0, 50, 0L, false));
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------
    // authEvents (GET .../{credentialId}/auth-events)
    // ---------------------------------------------------------------

    @Test
    void authEvents_anonymous_unauthorized() throws Exception {
        mvc.perform(get(AUTH_EVENTS_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "SOMETHING_ELSE")
    void authEvents_withoutRequiredRole_forbiddenAtControllerGate() throws Exception {
        mvc.perform(get(AUTH_EVENTS_PATH)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
    void authEvents_platformOperator_ok() throws Exception {
        org.mockito.Mockito.when(service.listAuthEvents(any(UUID.class), anyString(), anyInt(), anyInt()))
                .thenReturn(new PageView<>(List.of(), 0, 50, 0L, false));
        mvc.perform(get(AUTH_EVENTS_PATH)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void authEvents_rpAdmin_ok() throws Exception {
        org.mockito.Mockito.when(service.listAuthEvents(any(UUID.class), anyString(), anyInt(), anyInt()))
                .thenReturn(new PageView<>(List.of(), 0, 50, 0L, false));
        mvc.perform(get(AUTH_EVENTS_PATH)).andExpect(status().isOk());
    }
}
