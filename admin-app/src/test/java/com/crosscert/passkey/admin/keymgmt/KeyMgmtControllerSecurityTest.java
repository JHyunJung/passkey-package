package com.crosscert.passkey.admin.keymgmt;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = KeyMgmtController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration.class
        }
)
@Import({
        com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
        KeyMgmtControllerSecurityTest.JpaStubs.class
})
class KeyMgmtControllerSecurityTest {

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

    // Key-mgmt specific beans
    @MockitoBean com.crosscert.passkey.core.repository.SigningKeyRepository repo;
    @MockitoBean KeyRotationService rotation;
    @MockitoBean com.crosscert.passkey.core.repository.AdminUserRepository admins;

    // Beans required by AdminSecurityConfig / AdminApplication context
    @MockitoBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockitoBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean java.time.Clock clock;
    @MockitoBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    // AdminSecurityConfig now wires a DynamicCorsConfigurationSource constructor param.
    @MockitoBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;

    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
    @MockitoBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockitoBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockitoBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.ApiKeyScopeRepository apiKeyScopeRepository;
    @MockitoBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockitoBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminSignupRequestRepository adminSignupRequestRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository adminPasswordResetTokenRepository;
    @MockitoBean com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository adminUserRecoveryCodeRepository;
    @MockitoBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockitoBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;

    @Test
    void anonymousListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/keys")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCannotRotate() throws Exception {
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void adminCanRotate() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(
                java.util.Optional.of(adminUserWithUuid()));
        when(rotation.rotate(any(UUID.class), anyString()))
                .thenReturn(new KeyRotationService.RotateResult("old-kid", "new-kid"));
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.oldKid").value("old-kid"))
                .andExpect(jsonPath("$.data.newKid").value("new-kid"));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void rotateConflictWhenLeaseUnavailable() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(
                java.util.Optional.of(adminUserWithUuid()));
        when(rotation.rotate(any(UUID.class), anyString()))
                .thenThrow(new com.crosscert.passkey.core.api.BusinessException(
                        com.crosscert.passkey.core.api.ErrorCode.KEY_ROTATION_CONFLICT));
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("S001"))
                .andExpect(jsonPath("$.error.errorCode").value("S001"));
    }

    private static com.crosscert.passkey.core.entity.AdminUser adminUserWithUuid() {
        var u = new com.crosscert.passkey.core.entity.AdminUser("alice@example.com", "x", "ADMIN");
        try {
            // Phase 8 T3: id moved to BaseEntity superclass.
            var f = com.crosscert.passkey.core.entity.AdminUser.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return u;
    }
}
