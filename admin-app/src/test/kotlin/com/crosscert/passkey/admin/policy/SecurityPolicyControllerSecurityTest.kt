package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.admin.web.AdminWebSliceTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime

@AdminWebSliceTest(controllers = [SecurityPolicyController::class])
class SecurityPolicyControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var service: SecurityPolicyService

    private companion object {
        fun sampleView(): SecurityPolicyDto.View = SecurityPolicyDto.View(
            30, false, listOf(), OffsetDateTime.parse("2026-05-31T00:00:00Z"), "alice@example.com",
        )
    }

    @Test
    fun anonymousGetIsUnauthorized() {
        mvc.perform(get("/admin/api/security-policy")).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun rpAdminGetIsForbidden() {
        mvc.perform(get("/admin/api/security-policy"))
            .andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun platformOperatorCanGet() {
        whenever(service.get()).thenReturn(sampleView())
        mvc.perform(get("/admin/api/security-policy"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionIdleTimeoutMinutes").value(30))
            .andExpect(jsonPath("$.updatedBy").value("alice@example.com"))
    }
}
