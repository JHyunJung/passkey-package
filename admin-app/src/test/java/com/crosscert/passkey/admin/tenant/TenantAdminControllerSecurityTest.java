package com.crosscert.passkey.admin.tenant;

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

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TenantAdminController.class)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    com.crosscert.passkey.core.api.GlobalExceptionHandler.class
})
class TenantAdminControllerSecurityTest {

    /**
     * The real {@code AdminApplication} carries {@code @EnableJpaRepositories}, which
     * eagerly bootstraps every Spring Data JPA repository factory — and that blows up in
     * a web slice that has no real {@code EntityManagerFactory}/{@code Metamodel}
     * ("The given domain class can not be found in the given Metamodel"). Declaring this
     * nested {@code @SpringBootConfiguration} short-circuits {@code @WebMvcTest}'s upward
     * search for the primary config, so {@code AdminApplication} (and its JPA wiring) is
     * never loaded. {@code @EnableAutoConfiguration} keeps the web/security auto-configs
     * the slice needs; the controller-under-test is supplied via {@code @Import} (rather
     * than a {@code @ComponentScan}, which would also pull in unrelated {@code @Component}
     * beans in the same package). Every controller collaborator is a {@code @MockBean}.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(TenantAdminController.class)
    static class SliceConfig {
    }

    @Autowired MockMvc mvc;
    @MockBean TenantAdminService service;
    @MockBean WebauthnDiffService webauthnDiffService;
    @MockBean TenantLifecycleService lifecycle;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;

    private static final String BODY = """
            {"slug":"tenant-a","displayName":"Tenant A","rpId":"localhost","rpName":"Tenant A",
             "allowedOrigins":["http://localhost"],
             "acceptedFormats":["none","packed"],
             "requireUserVerification":true,
             "mdsRequired":false,
             "attestationConveyance":"NONE",
             "webauthnTimeoutMs":60000}
            """;

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCanGet() throws Exception {
        when(service.list()).thenReturn(java.util.List.of());
        mvc.perform(get("/admin/api/tenants"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCannotPost() throws Exception {
        mvc.perform(post("/admin/api/tenants")
                .with(csrf())
                .contentType("application/json")
                .content(BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void adminCanPost() throws Exception {
        when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithUuid()));
        when(service.create(any(), any(java.util.UUID.class), anyString()))
                .thenReturn(new TenantAdminDto.TenantView(
                        java.util.UUID.randomUUID(), "tenant-a", "Tenant A", "active",
                        "localhost", "Tenant A",
                        List.of("http://localhost"),
                        Set.of("none", "packed"),
                        true, false,
                        "NONE", 60000,
                        0L, 0L, null,
                        java.time.Instant.now(), java.time.Instant.now()));
        mvc.perform(post("/admin/api/tenants")
                .with(csrf())
                .contentType("application/json")
                .content(BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Tenant created"))
            .andExpect(jsonPath("$.data.slug").value("tenant-a"))
            .andExpect(jsonPath("$.data.allowedOrigins[0]").value("http://localhost"))
            .andExpect(jsonPath("$.data.acceptedFormats", hasItems("none", "packed")))
            .andExpect(jsonPath("$.data.requireUserVerification").value(true))
            .andExpect(jsonPath("$.data.mdsRequired").value(false));
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void getReturnsApiErrorWhenTenantNotFound() throws Exception {
        when(service.get("missing"))
            .thenThrow(new com.crosscert.passkey.core.api.BusinessException(
                com.crosscert.passkey.core.api.ErrorCode.TENANT_NOT_FOUND));
        mvc.perform(get("/admin/api/tenants/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("T001"))
            .andExpect(jsonPath("$.error.errorCode").value("T001"));
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCannotSuspend() throws Exception {
        mvc.perform(post("/admin/api/tenants/acme/suspend").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void operatorCanSuspend() throws Exception {
        java.util.UUID tenantId = java.util.UUID.randomUUID();
        when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithUuid()));
        when(service.get("acme")).thenReturn(new TenantAdminDto.TenantView(
                tenantId, "acme", "Acme", "active",
                "localhost", "Acme",
                List.of("http://localhost"), Set.of("none"),
                true, false, "NONE", 60000,
                0L, 0L, null,
                java.time.Instant.now(), java.time.Instant.now()));
        mvc.perform(post("/admin/api/tenants/acme/suspend").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    private static com.crosscert.passkey.core.entity.AdminUser adminUserWithUuid() {
        var u = new com.crosscert.passkey.core.entity.AdminUser("alice@example.com", "x", "ADMIN");
        try {
            // Phase 8 T3: id moved to BaseEntity superclass.
            var f = com.crosscert.passkey.core.entity.AdminUser.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, java.util.UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return u;
    }
}
