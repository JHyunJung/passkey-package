package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.config.DbSchemaProperties
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AdminWebSliceTest(controllers = [MdsAdminController::class])
// MdsAdminController 가 raw SQL 스키마 prefix 로 쓰는 실제 빈(passkey.db.schema 는 application-common.yml).
@Import(DbSchemaProperties::class)
class MdsAdminControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var jdbc: JdbcTemplate

    @MockitoBean
    lateinit var scheduler: MdsSchedulerService

    @MockitoBean
    lateinit var historyService: MdsHistoryService

    @MockitoBean
    lateinit var redis: StringRedisTemplate

    @Test
    fun anonymousGetStatusIsUnauthorized() {
        mvc.perform(get("/admin/api/mds/status")).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun viewerCannotForceSync() {
        mvc.perform(post("/admin/api/mds/sync").with(csrf()))
            .andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun adminCanForceSync() {
        whenever(scheduler.runOnce())
            .thenReturn(MdsSchedulerService.SyncResult.synced(42L))
        mvc.perform(post("/admin/api/mds/sync").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").exists())
    }
}
