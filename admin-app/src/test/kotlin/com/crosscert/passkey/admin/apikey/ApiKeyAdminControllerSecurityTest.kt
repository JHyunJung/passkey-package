package com.crosscert.passkey.admin.apikey

import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

@AdminWebSliceTest(controllers = [ApiKeyAdminController::class])
class ApiKeyAdminControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var service: ApiKeyAdminService

    @Autowired
    lateinit var admins: AdminUserRepository

    @Test
    fun anonymousGetIsUnauthorized() {
        mvc.perform(get("/admin/api/api-keys")).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun viewerCanList() {
        mvc.perform(get("/admin/api/api-keys"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun adminCanIssue() {
        whenever(admins.findByEmail(anyString()))
            .thenReturn(Optional.of(adminUserWithUuid()))
        // actorId 는 Kotlin 시그니처상 UUID? — 원 Java any(UUID.class) → anyOrNull() (Task 14 규칙).
        whenever(service.issue(anyOrNull(), anyOrNull(), anyString()))
            .thenReturn(
                ApiKeyAdminDto.ApiKeyCreateResponse(
                    UUID.randomUUID(), "pk_abcd1234SECRET", "pk_abcd1234",
                    setOf("registration", "authentication"), null,
                ),
            )
        mvc.perform(
            post("/admin/api/api-keys")
                .with(csrf()).contentType("application/json").content(BODY),
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("API key issued"))
            .andExpect(jsonPath("$.data.plainText").exists())
            .andExpect(jsonPath("$.data.prefix").value("pk_abcd1234"))
            .andExpect(jsonPath("$.data.scopes", hasItems("registration", "authentication")))
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun adminCanRevoke() {
        whenever(admins.findByEmail(anyString()))
            .thenReturn(Optional.of(adminUserWithUuid()))
        mvc.perform(delete("/admin/api/api-keys/" + UUID.randomUUID()).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun adminCanRotate() {
        whenever(admins.findByEmail(anyString()))
            .thenReturn(Optional.of(adminUserWithUuid()))
        whenever(service.rotate(any<UUID>(), anyOrNull(), anyString()))
            .thenReturn(
                ApiKeyAdminDto.ApiKeyRotateResponse(
                    UUID.randomUUID(), "pk_new12345SECRET", "pk_new12345",
                    setOf("registration"),
                    OffsetDateTime.parse("2026-06-02T00:00:00Z"),
                ),
            )
        mvc.perform(post("/admin/api/api-keys/" + UUID.randomUUID() + "/rotate").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("API key rotated"))
            .andExpect(jsonPath("$.data.plaintextKey").exists())
            .andExpect(jsonPath("$.data.prefix").value("pk_new12345"))
            .andExpect(jsonPath("$.data.oldKeyExpiresAt").exists())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun revokeReturnsApiErrorWhenNotFound() {
        val missingId = UUID.randomUUID()
        whenever(admins.findByEmail(anyString()))
            .thenReturn(Optional.of(adminUserWithUuid()))
        doThrow(BusinessException(ErrorCode.API_KEY_NOT_FOUND))
            .whenever(service).revoke(eq(missingId), anyOrNull(), anyString())
        mvc.perform(delete("/admin/api/api-keys/$missingId").with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("K001"))
    }

    private companion object {
        val TENANT_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        // 원 Java text block 과 같은 값(끝 개행 포함).
        val BODY =
            """{"tenantId":"00000000-0000-0000-0000-000000000001","name":"primary","scopes":["registration","authentication"]}""" +
                "\n"

        fun adminUserWithUuid(): AdminUser {
            val u = AdminUser("alice@example.com", "x", "ADMIN")
            try {
                // Phase 8 T3: id moved to BaseEntity superclass.
                val f = AdminUser::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                f.set(u, UUID.randomUUID())
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
            return u
        }
    }
}
