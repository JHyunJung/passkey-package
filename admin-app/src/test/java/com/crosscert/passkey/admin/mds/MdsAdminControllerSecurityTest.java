package com.crosscert.passkey.admin.mds;

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
        controllers = MdsAdminController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
        }
)
@Import({
        com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
        MdsAdminControllerSecurityTest.JpaStubs.class
})
class MdsAdminControllerSecurityTest {

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
    @MockBean JdbcTemplate jdbc;
    @MockBean MdsSchedulerService scheduler;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeys;
    @MockBean com.crosscert.passkey.core.repository.MdsBlobCacheRepository mdsBlobCacheRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;

    @Test
    void anonymousGetStatusIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/mds/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanGetStatus() throws Exception {
        when(jdbc.queryForObject(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(new MdsStatusView(0L, null, null));
        mvc.perform(get("/admin/api/mds/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").exists());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotForceSync() throws Exception {
        mvc.perform(post("/admin/api/mds/sync").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanForceSync() throws Exception {
        when(scheduler.runOnce())
                .thenReturn(MdsSchedulerService.SyncResult.synced(42L));
        mvc.perform(post("/admin/api/mds/sync").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").exists());
    }
}
