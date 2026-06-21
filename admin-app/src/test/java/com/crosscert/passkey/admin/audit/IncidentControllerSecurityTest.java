package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.SecurityIncident;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
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

/**
 * AuditChainMonitorController 의 incident 엔드포인트 권한 게이팅 + 409/422 예외 매핑 검증.
 *
 * <p>슬라이스 부트 구성(@WebMvcTest + JpaStubs + MockBean 세트)은 같은 패키지의
 * AuditLogControllerSecurityTest / TenantAdminControllerSecurityTest 와 동일하게 복제한다
 * (AdminSecurityConfig 새 빈 누락 시 컨텍스트 로드 실패 회귀를 피하기 위해).
 */
@WebMvcTest(
    controllers = AuditChainMonitorController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    com.crosscert.passkey.core.api.GlobalExceptionHandler.class,
    IncidentControllerSecurityTest.JpaStubs.class
})
class IncidentControllerSecurityTest {

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

    // Controller collaborators
    @MockBean AuditChainVerifier verifier;
    @MockBean AuditChainBackfillService backfillService;
    @MockBean SecurityIncidentService incidents;
    @MockBean java.time.Clock clock;

    // Security config + JPA wiring stubs (mirrors AuditLogControllerSecurityTest)
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenants;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditRepo;
    @MockBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository creds;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeys;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyScopeRepository apiKeyScopeRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserInvitationRepository invitationRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository adminPasswordResetTokenRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository adminUserRecoveryCodeRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityIncidentRepository securityIncidentRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialAuthEventRepository credentialAuthEventRepository;
    @MockBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;
    @MockBean com.crosscert.passkey.admin.policy.SecurityPolicyService securityPolicyService;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    // tamperedEntryId 는 요청에 없다 — 서버가 도출한다. body 는 tenantId 만.
    private static final String CREATE_BODY = "{\"tenantId\":\"" + TENANT + "\"}";

    /** OPEN incident with a deterministic id for toView() stubbing. */
    private SecurityIncident openIncident() {
        return SecurityIncident.open(TENANT, ENTRY, "{}", ACTOR,
                OffsetDateTime.now(ZoneOffset.ofHours(9)));
    }

    private static AdminUser adminUserWithUuid() {
        var u = new AdminUser("alice@example.com", "x", "ADMIN");
        try {
            var f = AdminUser.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, ACTOR);
        } catch (Exception e) { throw new RuntimeException(e); }
        return u;
    }

    // ---- 권한 게이팅 -------------------------------------------------------

    @Test
    void anonymousCreateIsUnauthorized() throws Exception {
        mvc.perform(post("/admin/api/audit/chain/incidents")
                        .with(csrf())
                        .contentType("application/json")
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void rpAdminCannotCreate() throws Exception {
        mvc.perform(post("/admin/api/audit/chain/incidents")
                        .with(csrf())
                        .contentType("application/json")
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void rpAdminCannotList() throws Exception {
        mvc.perform(get("/admin/api/audit/chain/incidents"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void operatorCanCreate() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()));
        // create 는 3-arg(tenantId, actorId, actorEmail) — tamperedEntryId 는 서버가 도출.
        when(incidents.create(any(), any(), anyString())).thenReturn(openIncident());

        mvc.perform(post("/admin/api/audit/chain/incidents")
                        .with(csrf())
                        .contentType("application/json")
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.tamperedEntryId").value(ENTRY.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.severity").value("CRITICAL"));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void operatorCanList() throws Exception {
        when(incidents.list()).thenReturn(java.util.List.of(openIncident()));

        mvc.perform(get("/admin/api/audit/chain/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    // ---- 예외 매핑 (409 / 422 / 404 / 400) --------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void conflictMapsTo409() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()));
        when(incidents.create(any(), any(), anyString()))
                .thenThrow(new IncidentConflictException("open incident already exists"));

        mvc.perform(post("/admin/api/audit/chain/incidents")
                        .with(csrf())
                        .contentType("application/json")
                        .content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void notTamperedMapsTo422() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()));
        when(incidents.create(any(), any(), anyString()))
                .thenThrow(new IncidentNotTamperedException("tenant chain is intact"));

        mvc.perform(post("/admin/api/audit/chain/incidents")
                        .with(csrf())
                        .contentType("application/json")
                        .content(CREATE_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("not_tampered"));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void resolveNotFoundMapsTo404() throws Exception {
        UUID id = UUID.randomUUID();
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()));
        when(incidents.resolve(any(), anyString(), any(), anyString()))
                .thenThrow(new IncidentNotFoundException("no incident with id: " + id));

        mvc.perform(post("/admin/api/audit/chain/incidents/" + id + "/resolve")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void invalidTenantIdMapsTo400() throws Exception {
        // UUID.fromString("not-a-uuid") → IllegalArgumentException → GlobalExceptionHandler 400.
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()));

        mvc.perform(post("/admin/api/audit/chain/incidents")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"tenantId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void blankResolveNoteMapsTo400() throws Exception {
        // @NotBlank note → MethodArgumentNotValidException → GlobalExceptionHandler 400.
        UUID id = UUID.randomUUID();
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()));

        mvc.perform(post("/admin/api/audit/chain/incidents/" + id + "/resolve")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- resolve happy path -----------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void operatorCanResolve() throws Exception {
        SecurityIncident incident = openIncident();
        incident.resolve(ACTOR, "DBA 복구 완료", OffsetDateTime.now(ZoneOffset.ofHours(9)));
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()));
        when(incidents.resolve(any(), anyString(), any(), anyString())).thenReturn(incident);

        mvc.perform(post("/admin/api/audit/chain/incidents/" + incident.getId() + "/resolve")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"note\":\"DBA 복구 완료\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("DBA 복구 완료"));
    }
}
