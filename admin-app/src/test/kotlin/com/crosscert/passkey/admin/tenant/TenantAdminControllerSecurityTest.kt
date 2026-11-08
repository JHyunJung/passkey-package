package com.crosscert.passkey.admin.tenant

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
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

@AdminWebSliceTest(controllers = [TenantAdminController::class])
class TenantAdminControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var service: TenantAdminService

    @MockitoBean
    lateinit var webauthnDiffService: WebauthnDiffService

    @MockitoBean
    lateinit var lifecycle: TenantLifecycleService

    @Autowired
    lateinit var admins: AdminUserRepository

    private companion object {
        // 원 Java text block 과 같은 값(끝 개행 포함).
        val BODY =
            """
            {"slug":"tenant-a","displayName":"Tenant A","rpId":"localhost","rpName":"Tenant A",
             "allowedOrigins":["http://localhost"],
             "acceptedFormats":["none","packed"],
             "requireUserVerification":true,
             "mdsRequired":false,
             "attestationConveyance":"NONE",
             "webauthnTimeoutMs":60000}
            """.trimIndent() + "\n"

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

    @Test
    fun anonymousGetIsUnauthorized() {
        mvc.perform(get("/admin/api/tenants")).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun viewerCanGet() {
        whenever(service.list()).thenReturn(listOf())
        mvc.perform(get("/admin/api/tenants"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data").isArray())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun viewerCannotPost() {
        mvc.perform(
            post("/admin/api/tenants")
                .with(csrf())
                .contentType("application/json")
                .content(BODY),
        )
            .andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun adminCanPost() {
        whenever(admins.findByEmail(anyString()))
            .thenReturn(Optional.of(adminUserWithUuid()))
        // actorId: 원 Java 의 typed UUID 매처(null 불일치) 의미 그대로 — UUID? 파라미터에도 대입 가능.
        whenever(service.create(anyOrNull(), any<UUID>(), anyString()))
            .thenReturn(
                TenantAdminDto.TenantView(
                    UUID.randomUUID(), "tenant-a", "Tenant A", "active",
                    "localhost", "Tenant A",
                    listOf("http://localhost"),
                    setOf("none", "packed"),
                    true, false,
                    "NONE", 60000,
                    0L, 0L, null,
                    OffsetDateTime.now(), OffsetDateTime.now(),
                ),
            )
        mvc.perform(
            post("/admin/api/tenants")
                .with(csrf())
                .contentType("application/json")
                .content(BODY),
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Tenant created"))
            .andExpect(jsonPath("$.data.slug").value("tenant-a"))
            .andExpect(jsonPath("$.data.allowedOrigins[0]").value("http://localhost"))
            .andExpect(jsonPath("$.data.acceptedFormats", hasItems("none", "packed")))
            .andExpect(jsonPath("$.data.requireUserVerification").value(true))
            .andExpect(jsonPath("$.data.mdsRequired").value(false))
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun getReturnsApiErrorWhenTenantNotFound() {
        whenever(service.get("missing"))
            .thenThrow(BusinessException(ErrorCode.TENANT_NOT_FOUND))
        mvc.perform(get("/admin/api/tenants/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("T001"))
            .andExpect(jsonPath("$.error.errorCode").value("T001"))
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun viewerCannotSuspend() {
        mvc.perform(post("/admin/api/tenants/acme/suspend").with(csrf()))
            .andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun operatorCanSuspend() {
        val tenantId = UUID.randomUUID()
        whenever(admins.findByEmail(anyString()))
            .thenReturn(Optional.of(adminUserWithUuid()))
        whenever(service.get("acme")).thenReturn(
            TenantAdminDto.TenantView(
                tenantId, "acme", "Acme", "active",
                "localhost", "Acme",
                listOf("http://localhost"), setOf("none"),
                true, false, "NONE", 60000,
                0L, 0L, null,
                OffsetDateTime.now(), OffsetDateTime.now(),
            ),
        )
        mvc.perform(post("/admin/api/tenants/acme/suspend").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
    }
}
