package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AdminWebSliceTest(controllers = AuditLogController.class)
class AuditLogControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditChainVerifier verifier;
    @Autowired com.crosscert.passkey.admin.audit.AuditLogService audit;

    @Test
    void anonymousGetListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void viewerCannotVerify() throws Exception {
        mvc.perform(get("/admin/api/audit/verify")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
    void adminCanVerify() throws Exception {
        // NOTE: T9 renamed the static factory from ok() to valid() because
        // Java records auto-generate an accessor method named ok() for the ok field.
        when(verifier.verify()).thenReturn(AuditChainVerifier.Result.valid());
        mvc.perform(get("/admin/api/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ok").exists());
    }
}
