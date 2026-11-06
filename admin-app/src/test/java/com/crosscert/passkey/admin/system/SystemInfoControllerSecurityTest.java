package com.crosscert.passkey.admin.system;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = SystemInfoController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
        org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    SystemInfoControllerSecurityTest.JpaStubs.class
})
class SystemInfoControllerSecurityTest {

    /**
     * AdminApplication carries @EnableJpaRepositories which registers
     * jpaSharedEM_entityManagerFactory even in @WebMvcTest slices.
     * We provide a stub EMF with a real (empty) Metamodel so the
     * JPA wiring succeeds without a real DataSource.
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
    @MockitoBean SystemInfoService service;
    @MockitoBean AdminUserRepository admins;
    @MockitoBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockitoBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockitoBean java.time.Clock clock;
    @MockitoBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    // AdminSecurityConfig wires a DynamicCorsConfigurationSource constructor param.
    @MockitoBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    // AdminSecurityConfig 의 adminFilterChain 이 주입받는 두 빈 — 슬라이스에는 스캔되지 않는다.
    @MockitoBean com.crosscert.passkey.admin.policy.SecurityPolicyService securityPolicyService;
    @MockitoBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;
    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
    @MockitoBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockitoBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockitoBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.ApiKeyScopeRepository apiKeyScopeRepository;
    @MockitoBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockitoBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminSignupRequestRepository adminSignupRequestRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository adminPasswordResetTokenRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository adminUserRecoveryCodeRepository;
    @MockitoBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SecurityIncidentRepository securityIncidentRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminUserTenantRepository adminUserTenantRepository;
    @MockitoBean com.crosscert.passkey.core.repository.CredentialAuthEventRepository credentialAuthEventRepository;

    private static SystemInfoView sampleView() {
        // Minimal construction: nested Host null, empty components — only $.success is asserted.
        return new SystemInfoView(
                "1.0.0", "2026-05-31T09:00+09:00",
                null, null, List.<SystemInfoView.Component>of());
    }

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/system/info")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void rpAdminGetIsForbidden() throws Exception {
        mvc.perform(get("/admin/api/system/info"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void platformOperatorCanGet() throws Exception {
        when(service.get()).thenReturn(sampleView());
        mvc.perform(get("/admin/api/system/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
