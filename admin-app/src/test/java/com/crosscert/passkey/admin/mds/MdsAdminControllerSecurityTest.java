package com.crosscert.passkey.admin.mds;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AdminWebSliceTest(controllers = MdsAdminController.class)
// MdsAdminController 가 raw SQL 스키마 prefix 로 쓰는 실제 빈(passkey.db.schema 는 application-common.yml).
@org.springframework.context.annotation.Import(com.crosscert.passkey.core.config.DbSchemaProperties.class)
class MdsAdminControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean MdsSchedulerService scheduler;
    @MockitoBean MdsHistoryService historyService;
    @MockitoBean org.springframework.data.redis.core.StringRedisTemplate redis;

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
