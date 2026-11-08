package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.admin.web.AdminWebSliceTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AdminWebSliceTest(controllers = [AuditLogController::class])
class AuditLogControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var verifier: AuditChainVerifier

    @Autowired
    lateinit var audit: AuditLogService

    @Test
    fun anonymousGetListIsUnauthorized() {
        mvc.perform(get("/admin/api/audit")).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun viewerCannotVerify() {
        mvc.perform(get("/admin/api/audit/verify")).andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun adminCanVerify() {
        // NOTE: T9 renamed the static factory from ok() to valid() because
        // Java records auto-generate an accessor method named ok() for the ok field.
        whenever(verifier.verify()).thenReturn(AuditChainVerifier.Result.valid())
        mvc.perform(get("/admin/api/audit/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.ok").exists())
    }
}
