package com.crosscert.passkey.admin.tenant;

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

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = TenantAdminController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    TenantAdminControllerSecurityTest.JpaStubs.class
})
class TenantAdminControllerSecurityTest {

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
    @MockBean TenantAdminService service;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;

    private static final String BODY = """
            {"id":"T_A","displayName":"Tenant A","rpId":"localhost","rpName":"Tenant A",
             "allowedOriginsJson":"[\\"http://localhost\\"]",
             "attestationPolicyJson":"{\\"acceptedFormats\\":[\\"none\\"],\\"requireUserVerification\\":true,\\"mdsRequired\\":false}"}
            """;

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanGet() throws Exception {
        mvc.perform(get("/admin/api/tenants")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotPost() throws Exception {
        mvc.perform(post("/admin/api/tenants")
                .with(csrf())
                .contentType("application/json")
                .content(BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
    void adminCanPost() throws Exception {
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithId(7L)));
        org.mockito.Mockito.when(service.create(any(), anyLong(), anyString()))
                .thenReturn(new TenantAdminDto.TenantView(
                        "T_A","Tenant A","active","localhost","Tenant A",
                        "[]", "{}", java.time.Instant.now(), java.time.Instant.now()));
        mvc.perform(post("/admin/api/tenants")
                .with(csrf())
                .contentType("application/json")
                .content(BODY))
            .andExpect(status().isCreated());
    }

    private static com.crosscert.passkey.core.entity.AdminUser adminUserWithId(long id) {
        var u = new com.crosscert.passkey.core.entity.AdminUser("alice@example.com", "x", "ADMIN");
        try {
            var f = com.crosscert.passkey.core.entity.AdminUser.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (Exception e) { throw new RuntimeException(e); }
        return u;
    }
}
