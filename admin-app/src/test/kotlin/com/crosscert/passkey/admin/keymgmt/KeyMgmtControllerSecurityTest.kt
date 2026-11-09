package com.crosscert.passkey.admin.keymgmt

import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional
import java.util.UUID

@AdminWebSliceTest(controllers = [KeyMgmtController::class])
class KeyMgmtControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var rotation: KeyRotationService

    @Autowired
    lateinit var admins: AdminUserRepository

    @MockitoBean
    lateinit var jdbc: JdbcTemplate

    @Test
    fun anonymousListIsUnauthorized() {
        mvc.perform(get("/admin/api/keys")).andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun viewerCannotRotate() {
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
            .andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun adminCanRotate() {
        whenever(admins.findByEmail(anyString())).thenReturn(
            Optional.of(adminUserWithUuid()),
        )
        whenever(rotation.rotate(any<UUID>(), anyString()))
            .thenReturn(KeyRotationService.RotateResult("old-kid", "new-kid"))
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.oldKid").value("old-kid"))
            .andExpect(jsonPath("$.data.newKid").value("new-kid"))
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun rotateConflictWhenLeaseUnavailable() {
        whenever(admins.findByEmail(anyString())).thenReturn(
            Optional.of(adminUserWithUuid()),
        )
        whenever(rotation.rotate(any<UUID>(), anyString()))
            .thenThrow(BusinessException(ErrorCode.KEY_ROTATION_CONFLICT))
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("S001"))
            .andExpect(jsonPath("$.error.errorCode").value("S001"))
    }

    private companion object {
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
