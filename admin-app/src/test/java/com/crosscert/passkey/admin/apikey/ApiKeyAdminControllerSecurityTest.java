package com.crosscert.passkey.admin.apikey;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = ApiKeyAdminController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    ApiKeyAdminControllerSecurityTest.JpaStubs.class
})
class ApiKeyAdminControllerSecurityTest {

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
    @MockBean ApiKeyAdminService service;
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
    @MockBean com.crosscert.passkey.core.repository.MdsBlobCacheRepository mdsBlobCacheRepository;

    private static final String BODY = """
            {"tenantId":"T_A","name":"primary","scopesJson":"[]"}
            """;

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/api-keys")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanList() throws Exception {
        mvc.perform(get("/admin/api/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotIssue() throws Exception {
        mvc.perform(post("/admin/api/api-keys")
                .with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotRevoke() throws Exception {
        mvc.perform(delete("/admin/api/api-keys/42").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
    void adminCanIssue() throws Exception {
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithId(7L)));
        org.mockito.Mockito.when(service.issue(any(), anyLong(), anyString()))
                .thenReturn(new ApiKeyAdminDto.ApiKeyCreateResponse(
                        1L, "pk_abcd1234", "pk_abcd1234SECRET", "primary",
                        "T_A", java.time.Instant.now(), null));
        mvc.perform(post("/admin/api/api-keys")
                .with(csrf()).contentType("application/json").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("API key issued"))
                .andExpect(jsonPath("$.data.plainText").exists());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
    void adminCanRevoke() throws Exception {
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithId(7L)));
        mvc.perform(delete("/admin/api/api-keys/42").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
    void revokeReturnsApiErrorWhenNotFound() throws Exception {
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithId(7L)));
        org.mockito.Mockito.doThrow(new com.crosscert.passkey.core.api.BusinessException(
                        com.crosscert.passkey.core.api.ErrorCode.API_KEY_NOT_FOUND))
                .when(service).revoke(org.mockito.ArgumentMatchers.eq(999L), anyLong(), anyString());
        mvc.perform(delete("/admin/api/api-keys/999").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("K001"));
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
