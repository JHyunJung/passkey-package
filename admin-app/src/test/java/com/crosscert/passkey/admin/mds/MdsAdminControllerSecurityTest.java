package com.crosscert.passkey.admin.mds;

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

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MdsAdminController.class)
@Import({
        com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
        com.crosscert.passkey.core.api.GlobalExceptionHandler.class
})
class MdsAdminControllerSecurityTest {

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
    @Import(MdsAdminController.class)
    static class SliceConfig {
    }

    @Autowired MockMvc mvc;
    @MockBean JdbcTemplate jdbc;
    @MockBean MdsSchedulerService scheduler;
    @MockBean MdsHistoryService historyService;
    @MockBean org.springframework.data.redis.core.StringRedisTemplate redis;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;

    @Test
    void anonymousGetStatusIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/mds/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCannotForceSync() throws Exception {
        mvc.perform(post("/admin/api/mds/sync").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
    void adminCanForceSync() throws Exception {
        when(scheduler.runOnce())
                .thenReturn(MdsSchedulerService.SyncResult.synced(42L));
        mvc.perform(post("/admin/api/mds/sync").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").exists());
    }
}
