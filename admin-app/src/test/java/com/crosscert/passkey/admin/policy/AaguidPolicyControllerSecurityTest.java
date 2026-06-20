package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = AaguidPolicyController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    AaguidPolicyControllerSecurityTest.JpaStubs.class
})
class AaguidPolicyControllerSecurityTest {

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
    @MockBean AaguidPolicyService service;
    @MockBean SecurityPolicyService securityPolicyService;
    @MockBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    // AdminSecurityConfig wires a DynamicCorsConfigurationSource constructor param.
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialAuthEventRepository credentialAuthEventRepository;
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

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static AaguidPolicyDto.View sampleView() {
        return new AaguidPolicyDto.View(
                TENANT,
                com.crosscert.passkey.core.entity.TenantAaguidPolicy.Mode.ANY,
                false,
                List.of(),
                OffsetDateTime.parse("2026-05-31T00:00:00Z"),
                "alice@example.com");
    }

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void platformOperatorCanGet() throws Exception {
        when(service.get(any())).thenReturn(sampleView());
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "bob@example.com", roles = "RP_ADMIN")
    void rpAdminCanGet() throws Exception {
        when(service.get(any())).thenReturn(sampleView());
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isOk());
    }
}
