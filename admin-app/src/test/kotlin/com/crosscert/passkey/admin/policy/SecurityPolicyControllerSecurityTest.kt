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

    private fun putCors(vararg origins: String) =
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/admin/api/security-policy")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"sessionIdleTimeoutMinutes\":30,\"mfaRequired\":false,\"corsAllowlist\":[" +
                        origins.joinToString(",") { "\"$it\"" } + "]}",
                ),
        )

    // 화면(SecurityPolicyTabController)과 같은 PolicyValidation 규칙을 REST 에도 — allowCredentials=true 라
    // "*" 는 Spring 이 요청마다 거부하고, 경로가 붙은 값은 영원히 매칭되지 않는다.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
        strings = ["*", "https://a.example.com/path", "a.example.com", "http://evil.example.com", "https://*.example.com"],
    )
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun putRejectsMalformedOrigin(origin: String) {
        putCors(origin).andExpect(status().isBadRequest())
        org.mockito.kotlin.verifyNoInteractions(service)
    }

    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun putAcceptsWellFormedOrigins() {
        whenever(service.update(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())).thenReturn(sampleView())
        putCors("https://admin.example.com", "http://localhost:5173").andExpect(status().isOk())
    }
}
