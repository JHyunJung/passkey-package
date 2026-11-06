package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.repository.AuditLogRepository;
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

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = AuditLogController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
        org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    AuditLogControllerSecurityTest.JpaStubs.class
})
class AuditLogControllerSecurityTest {

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
            when(metamodel.getEntities()).thenReturn(Set.of());
            when(metamodel.getManagedTypes()).thenReturn(Set.of());
            when(metamodel.getEmbeddables()).thenReturn(Set.of());

            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean AuditLogRepository repo;
    @MockitoBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockitoBean AuditChainVerifier verifier;
    @MockitoBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockitoBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockitoBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockitoBean java.time.Clock clock;
    @MockitoBean com.crosscert.passkey.core.repository.AdminUserRepository admins;
    @MockitoBean com.crosscert.passkey.core.repository.TenantRepository tenants;
    @MockitoBean com.crosscert.passkey.core.repository.CredentialRepository creds;
    @MockitoBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeys;
    @MockitoBean com.crosscert.passkey.core.repository.ApiKeyScopeRepository apiKeyScopeRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockitoBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminSignupRequestRepository adminSignupRequestRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository adminPasswordResetTokenRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository adminUserRecoveryCodeRepository;
    @MockitoBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;
    @MockitoBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;
    // AdminSecurityConfig now wires a DynamicCorsConfigurationSource constructor param.
    @MockitoBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;

    @Test
    void anonymousGetListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCannotVerify() throws Exception {
        mvc.perform(get("/admin/api/audit/verify")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
    void adminCanVerify() throws Exception {
        // NOTE: T9 renamed the static factory from ok() to valid() because
        // Java records auto-generate an accessor method named ok() for the ok field.
        when(verifier.verify()).thenReturn(AuditChainVerifier.Result.valid());
        mvc.perform(get("/admin/api/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ok").exists());
    }
}
