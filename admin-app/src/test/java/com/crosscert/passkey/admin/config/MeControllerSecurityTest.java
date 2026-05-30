package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = MeController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    AdminSecurityConfig.class,
    MeControllerSecurityTest.JpaStubs.class
})
class MeControllerSecurityTest {

    /** Same JPA stub pattern as the sibling *ControllerSecurityTest slices. */
    @TestConfiguration
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
    @MockBean AdminUserRepository users;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.MdsBlobCacheRepository mdsBlobCacheRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserInvitationRepository invitationRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;

    private Authentication operator() {
        AdminUserDetails principal = new AdminUserDetails(
                UUID.randomUUID(), "alice@example.com", "x",
                "PLATFORM_OPERATOR", null, true, null, java.time.Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(
                principal, "x", principal.getAuthorities());
    }

    private static AdminUser adminUser(boolean mfaEnabled) {
        var u = new AdminUser("alice@example.com", "x", "ADMIN");
        u.setMfaEnabled(mfaEnabled);
        return u;
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returnsMfaEnabledAndMfaRequired_whenPending() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(true)));

        mvc.perform(get("/admin/api/me")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.email").value("alice@example.com"))
            .andExpect(jsonPath("$.data.role").value("PLATFORM_OPERATOR"))
            .andExpect(jsonPath("$.data.mfaEnabled").value(true))
            .andExpect(jsonPath("$.data.mfaRequired").value(true));
    }

    @Test
    void me_mfaRequiredFalse_whenNoPendingAttr() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(false)));

        mvc.perform(get("/admin/api/me")
                .with(authentication(operator())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mfaEnabled").value(false))
            .andExpect(jsonPath("$.data.mfaRequired").value(false));
    }
}
