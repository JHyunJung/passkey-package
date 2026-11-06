package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AdminWebSliceTest(controllers = AaguidPolicyController.class)
class AaguidPolicyControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean AaguidPolicyService service;

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static AaguidPolicyDto.View sampleView() {
        return new AaguidPolicyDto.View(
                TENANT,
                com.crosscert.passkey.core.entity.TenantAaguidPolicy.Mode.ANY,
                false,
                List.of(),
                OffsetDateTime.parse("2026-05-31T00:00:00Z"),
                "alice@example.com");
    }

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void platformOperatorCanGet() throws Exception {
        when(service.get(any())).thenReturn(sampleView());
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "bob@example.com", roles = "RP_ADMIN")
    void rpAdminCanGet() throws Exception {
        when(service.get(any())).thenReturn(sampleView());
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isOk());
    }
}
