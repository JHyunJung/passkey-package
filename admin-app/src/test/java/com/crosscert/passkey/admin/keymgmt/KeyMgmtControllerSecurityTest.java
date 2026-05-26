package com.crosscert.passkey.admin.keymgmt;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
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
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
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
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository repo;
    @MockBean KeyRotationService rotation;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;

    // Beans required by AdminSecurityConfig / AdminApplication context
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean JdbcTemplate jdbc;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;

    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.MdsBlobCacheRepository mdsBlobCacheRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;

    @Test
    void anonymousListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/keys")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanList() throws Exception {
        when(repo.findAll()).thenReturn(java.util.List.of());
        mvc.perform(get("/admin/api/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.keys").isArray());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotRotate() throws Exception {
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
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
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
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
            var f = com.crosscert.passkey.core.entity.AdminUser.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return u;
    }
}
