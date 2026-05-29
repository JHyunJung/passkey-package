package com.crosscert.passkey.admin.keymgmt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = KeyMgmtController.class)
@Import({
        com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
        com.crosscert.passkey.core.api.GlobalExceptionHandler.class
})
class KeyMgmtControllerSecurityTest {

    /**
     * The real {@code AdminApplication} carries {@code @EnableJpaRepositories}, which
     * eagerly bootstraps every Spring Data JPA repository factory — and that blows up in
     * a web slice that has no real {@code EntityManagerFactory}/{@code Metamodel}
     * ("The given domain class can not be found in the given Metamodel"). Declaring this
     * nested {@code @SpringBootConfiguration} short-circuits {@code @WebMvcTest}'s upward
     * search for the primary config, so {@code AdminApplication} (and its JPA wiring) is
     * never loaded. The controller-under-test is supplied via {@code @Import} (rather
     * than a {@code @ComponentScan}, which would also pull in unrelated {@code @Component}
     * jobs in the same package); every collaborator is a {@code @MockBean}.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(KeyMgmtController.class)
    static class SliceConfig {
    }

    @Autowired MockMvc mvc;

    // Key-mgmt specific beans
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository repo;
    @MockBean KeyRotationService rotation;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;

    // Beans required by AdminSecurityConfig
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean JdbcTemplate jdbc;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;

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
