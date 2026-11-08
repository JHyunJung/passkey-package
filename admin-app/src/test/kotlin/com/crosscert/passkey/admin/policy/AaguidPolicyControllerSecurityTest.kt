package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.entity.TenantAaguidPolicy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.UUID

@AdminWebSliceTest(controllers = [AaguidPolicyController::class])
class AaguidPolicyControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var service: AaguidPolicyService

    private companion object {
        val TENANT: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        fun sampleView(): AaguidPolicyDto.View = AaguidPolicyDto.View(
            TENANT,
            TenantAaguidPolicy.Mode.ANY,
            false,
            listOf(),
            OffsetDateTime.parse("2026-05-31T00:00:00Z"),
            "alice@example.com",
        )
    }

    @Test
    fun anonymousGetIsUnauthorized() {
        mvc.perform(get("/admin/api/tenants/$TENANT/aaguid-policy"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun platformOperatorCanGet() {
        whenever(service.get(anyOrNull())).thenReturn(sampleView())
        mvc.perform(get("/admin/api/tenants/$TENANT/aaguid-policy"))
            .andExpect(status().isOk())
    }

    @Test
    @WithMockUser(username = "bob@example.com", roles = ["RP_ADMIN"])
    fun rpAdminCanGet() {
        whenever(service.get(anyOrNull())).thenReturn(sampleView())
        mvc.perform(get("/admin/api/tenants/$TENANT/aaguid-policy"))
            .andExpect(status().isOk())
    }
}
