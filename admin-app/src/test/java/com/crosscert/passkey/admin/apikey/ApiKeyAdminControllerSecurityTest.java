package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ApiKeyAdminController.class)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    com.crosscert.passkey.core.api.GlobalExceptionHandler.class
})
class ApiKeyAdminControllerSecurityTest {

    /**
     * The real {@code AdminApplication} carries {@code @EnableJpaRepositories}, which
     * eagerly bootstraps every Spring Data JPA repository factory — and that blows up in
     * a web slice that has no real {@code EntityManagerFactory}/{@code Metamodel}
     * ("The given domain class can not be found in the given Metamodel"). Declaring this
     * nested {@code @SpringBootConfiguration} short-circuits {@code @WebMvcTest}'s upward
     * search for the primary config, so {@code AdminApplication} (and its JPA wiring) is
     * never loaded. The controller-under-test is supplied via {@code @Import} (rather
     * than a {@code @ComponentScan}, which would also pull in unrelated {@code @Component}
     * beans in the same package); every collaborator is a {@code @MockBean}.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ApiKeyAdminController.class)
    static class SliceConfig {
    }

    @Autowired MockMvc mvc;
    @MockBean ApiKeyAdminService service;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;

    private static final UUID TENANT_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String BODY = """
            {"tenantId":"00000000-0000-0000-0000-000000000001","name":"primary","scopes":["registration","authentication"]}
            """;

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/api-keys")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCanList() throws Exception {
        mvc.perform(get("/admin/api/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void adminCanIssue() throws Exception {
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithUuid()));
        org.mockito.Mockito.when(service.issue(any(), any(UUID.class), anyString()))
                .thenReturn(new ApiKeyAdminDto.ApiKeyCreateResponse(
                        UUID.randomUUID(), "pk_abcd1234SECRET", "pk_abcd1234",
                        java.util.Set.of("registration", "authentication")));
        mvc.perform(post("/admin/api/api-keys")
                .with(csrf()).contentType("application/json").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("API key issued"))
                .andExpect(jsonPath("$.data.plainText").exists())
                .andExpect(jsonPath("$.data.prefix").value("pk_abcd1234"))
                .andExpect(jsonPath("$.data.scopes", hasItems("registration", "authentication")));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void adminCanRevoke() throws Exception {
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithUuid()));
        mvc.perform(delete("/admin/api/api-keys/" + UUID.randomUUID()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void revokeReturnsApiErrorWhenNotFound() throws Exception {
        UUID missingId = UUID.randomUUID();
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithUuid()));
        org.mockito.Mockito.doThrow(new com.crosscert.passkey.core.api.BusinessException(
                        com.crosscert.passkey.core.api.ErrorCode.API_KEY_NOT_FOUND))
                .when(service).revoke(org.mockito.ArgumentMatchers.eq(missingId), any(UUID.class), anyString());
        mvc.perform(delete("/admin/api/api-keys/" + missingId).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("K001"));
    }

    private static com.crosscert.passkey.core.entity.AdminUser adminUserWithUuid() {
        var u = new com.crosscert.passkey.core.entity.AdminUser("alice@example.com", "x", "ADMIN");
        try {
            // Phase 8 T3: id moved to BaseEntity superclass.
            var f = com.crosscert.passkey.core.entity.AdminUser.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return u;
    }
}
