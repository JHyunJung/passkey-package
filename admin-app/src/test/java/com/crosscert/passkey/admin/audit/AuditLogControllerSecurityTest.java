package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditLogController.class)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    com.crosscert.passkey.core.api.GlobalExceptionHandler.class
})
class AuditLogControllerSecurityTest {

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
    @Import(AuditLogController.class)
    static class SliceConfig {
    }

    @Autowired MockMvc mvc;
    @MockBean AuditLogRepository repo;
    @MockBean AuditChainVerifier verifier;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean java.time.Clock clock;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;

    @Test
    void anonymousGetListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCannotVerify() throws Exception {
        mvc.perform(get("/admin/api/audit/verify")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
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
