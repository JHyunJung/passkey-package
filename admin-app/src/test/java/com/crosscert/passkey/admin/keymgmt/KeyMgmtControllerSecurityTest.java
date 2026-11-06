package com.crosscert.passkey.admin.keymgmt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AdminWebSliceTest(controllers = KeyMgmtController.class)
class KeyMgmtControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean KeyRotationService rotation;
    @Autowired com.crosscert.passkey.core.repository.AdminUserRepository admins;

    @MockitoBean JdbcTemplate jdbc;

    @Test
    void anonymousListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/keys")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCannotRotate() throws Exception {
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void adminCanRotate() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(
                java.util.Optional.of(adminUserWithUuid()));
        when(rotation.rotate(any(UUID.class), anyString()))
                .thenReturn(new KeyRotationService.RotateResult("old-kid", "new-kid"));
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.oldKid").value("old-kid"))
                .andExpect(jsonPath("$.data.newKid").value("new-kid"));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void rotateConflictWhenLeaseUnavailable() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(
                java.util.Optional.of(adminUserWithUuid()));
        when(rotation.rotate(any(UUID.class), anyString()))
                .thenThrow(new com.crosscert.passkey.core.api.BusinessException(
                        com.crosscert.passkey.core.api.ErrorCode.KEY_ROTATION_CONFLICT));
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("S001"))
                .andExpect(jsonPath("$.error.errorCode").value("S001"));
    }

    private static com.crosscert.passkey.core.entity.AdminUser adminUserWithUuid() {
        var u = new com.crosscert.passkey.core.entity.AdminUser("alice@example.com", "x", "ADMIN");
        try {
            // Phase 8 T3: id moved to BaseEntity superclass.
            var f = com.crosscert.passkey.core.entity.AdminUser.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return u;
    }
}
