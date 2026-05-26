package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.repository.AuditLogRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = AuditLogController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
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
    @MockBean AuditLogRepository repo;
    @MockBean AuditChainVerifier verifier;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean java.time.Clock clock;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenants;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository creds;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeys;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.MdsBlobCacheRepository mdsBlobCacheRepository;

    @Test
    void anonymousGetListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanList() throws Exception {
        when(repo.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.<com.crosscert.passkey.core.entity.AuditLog>of()));
        mvc.perform(get("/admin/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotVerify() throws Exception {
        mvc.perform(get("/admin/api/audit/verify")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
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
