package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AdminWebSliceTest(controllers = SecurityPolicyController.class)
class SecurityPolicyControllerSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired SecurityPolicyService service;

    private static SecurityPolicyDto.View sampleView() {
        return new SecurityPolicyDto.View(
                30, false, List.of(), OffsetDateTime.parse("2026-05-31T00:00:00Z"), "alice@example.com");
    }

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/security-policy")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void rpAdminGetIsForbidden() throws Exception {
        mvc.perform(get("/admin/api/security-policy"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void platformOperatorCanGet() throws Exception {
        when(service.get()).thenReturn(sampleView());
        mvc.perform(get("/admin/api/security-policy"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionIdleTimeoutMinutes").value(30))
            .andExpect(jsonPath("$.updatedBy").value("alice@example.com"));
    }
}
