package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.config.AdminSecurityConfig;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = SignupRequestController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    AdminSecurityConfig.class,
    SignupRequestControllerSecurityTest.JpaStubs.class
})
class SignupRequestControllerSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(SignupRequestController.class)
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            Metamodel metamodel = mock(Metamodel.class);
            when(metamodel.getEntities()).thenReturn(Set.of());
            when(metamodel.getManagedTypes()).thenReturn(Set.of());
            when(metamodel.getEmbeddables()).thenReturn(Set.of());
            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean SignupRequestService service;
    @MockBean AdminUserRepository users;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyScopeRepository apiKeyScopeRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository adminPasswordResetTokenRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository adminUserRecoveryCodeRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;
    @MockBean com.crosscert.passkey.admin.policy.SecurityPolicyService securityPolicyService;
    @MockBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;

    private static Authentication as(String role) {
        AdminUserDetails principal = new AdminUserDetails(
                UUID.randomUUID(), "who@example.com", "x",
                role, Set.of(), true, null, java.time.Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(principal, "x", principal.getAuthorities());
    }

    private static final String VALID_BODY =
            "{\"email\":\"new@example.com\",\"password\":\"password-12chars\",\"reason\":\"RP 담당\"}";

    // ── 공개 POST ──────────────────────────────────────────────────────────

    @Test
    void publicRequest_anonymousWithoutCsrf_isAccepted202() throws Exception {
        mvc.perform(post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.accepted").value(true));
        verify(service).request(any());
    }

    @Test
    void publicRequest_shortPassword_is400_andServiceNotCalled() throws Exception {
        mvc.perform(post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@example.com\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
        verify(service, never()).request(any());
    }

    @Test
    void publicRequest_invalidEmail_is400() throws Exception {
        mvc.perform(post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"password-12chars\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── 관리 GET ───────────────────────────────────────────────────────────

    @Test
    void list_anonymous_is401() throws Exception {
        mvc.perform(get("/admin/api/signup-requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_rpAdmin_is403() throws Exception {
        mvc.perform(get("/admin/api/signup-requests").with(authentication(as("RP_ADMIN"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void list_platformOperator_is200() throws Exception {
        when(service.list()).thenReturn(List.of());
        mvc.perform(get("/admin/api/signup-requests").with(authentication(as("PLATFORM_OPERATOR"))))
            .andExpect(status().isOk());
    }

    // ── approve / reject ───────────────────────────────────────────────────

    @Test
    void approve_anonymous_is401() throws Exception {
        mvc.perform(post("/admin/api/signup-requests/" + UUID.randomUUID() + "/approve")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void approve_rpAdmin_is403() throws Exception {
        mvc.perform(post("/admin/api/signup-requests/" + UUID.randomUUID() + "/approve")
                .with(authentication(as("RP_ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"))
            .andExpect(status().isForbidden());
        verify(service, never()).approve(any(), any(), any(), any());
    }

    @Test
    void approve_platformOperator_delegates() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.approve(any(), any(), any(), any())).thenReturn(new AdminUserDto.View(
                UUID.randomUUID(), "new@example.com", "PLATFORM_OPERATOR", "ACTIVE",
                List.of(), null, null, null, "who@example.com", false));
        mvc.perform(post("/admin/api/signup-requests/" + id + "/approve")
                .with(authentication(as("PLATFORM_OPERATOR"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("new@example.com"));
    }

    @Test
    void reject_platformOperator_is204() throws Exception {
        mvc.perform(post("/admin/api/signup-requests/" + UUID.randomUUID() + "/reject")
                .with(authentication(as("PLATFORM_OPERATOR"))).with(csrf()))
            .andExpect(status().isNoContent());
        verify(service).reject(any(), any(), any());
    }

    @Test
    void reject_rpAdmin_is403() throws Exception {
        mvc.perform(post("/admin/api/signup-requests/" + UUID.randomUUID() + "/reject")
                .with(authentication(as("RP_ADMIN"))).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
