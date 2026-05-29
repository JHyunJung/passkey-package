package com.crosscert.passkey.admin.license;

import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.core.license.LicenseLimits;
import com.crosscert.passkey.core.license.LicenseProperties;
import com.crosscert.passkey.core.license.LicenseState;
import com.crosscert.passkey.core.license.LicenseStateMachine;
import com.crosscert.passkey.core.license.LicenseToken;
import com.crosscert.passkey.core.license.LicenseGuardFilter;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L3.4 — LicenseGuardFilterIT: DEAD 상태에서 API 차단 / exempt 경로 통과 통합 테스트.
 *
 * <p>{@code @WebMvcTest} 슬라이스로 운영. LicenseGuardFilter + AdminSecurityConfig 를
 * 함께 로드하고, DeadStateConfig 가 만료된 토큰으로 LicenseStateMachine 을 제공하여
 * DEAD 상태를 인위적으로 만든다.
 *
 * <p>3 assertions:
 * <ol>
 *   <li>DEAD 상태에서 /admin/api/tenants → 503 (차단)</li>
 *   <li>DEAD 상태에서 /admin/api/license → 200 (exempt, L4.1 에서 enable)</li>
 *   <li>DEAD 상태에서 /actuator/health → 200 (exempt)</li>
 * </ol>
 */
@WebMvcTest(
        controllers = {TenantAdminController.class, LicenseController.class},
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
        }
)
@TestPropertySource(properties = {
        "passkey.deployment.mode=onprem",
        "passkey.license.path=/dev/null",
        "passkey.license.heartbeat-url=http://localhost:9"
})
@Import({
        com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
        LicenseGuardFilter.class,
        LicenseGuardFilterIT.DeadStateConfig.class,
        LicenseGuardFilterIT.JpaStubs.class
})
class LicenseGuardFilterIT {

    /**
     * Provides a LicenseStateMachine in DEAD state (token expired yesterday).
     * @Primary ensures this beats any other LicenseStateMachine candidate.
     */
    @TestConfiguration
    static class DeadStateConfig {

        @Bean
        LicenseProperties licenseProperties() {
            return new LicenseProperties(null, null, null, null, null, null);
        }

        @Bean
        @Primary
        LicenseStateMachine deadStateMachine() {
            Instant past = Instant.now().minus(Duration.ofDays(1));
            LicenseToken expiredToken = new LicenseToken(
                    "test-customer",
                    "lic-test-dead",
                    past.minus(Duration.ofDays(30)),
                    past.minus(Duration.ofDays(30)),
                    past,                                               // expiresAt = yesterday
                    UUID.nameUUIDFromBytes("test".getBytes()).toString(),
                    Set.of("mds"),
                    LicenseLimits.DEFAULTS);
            LicenseStateMachine sm = new LicenseStateMachine(
                    expiredToken, past, Instant::now);
            assert sm.state() == LicenseState.DEAD : "Precondition: state must be DEAD, got " + sm.state();
            return sm;
        }
    }

    /**
     * AdminApplication carries @EnableJpaRepositories which registers
     * jpaSharedEM_entityManagerFactory even in @WebMvcTest slices.
     * We provide a stub EMF with a real (empty) Metamodel so the
     * JPA wiring succeeds without a real DataSource.
     */
    @TestConfiguration
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            Metamodel metamodel = org.mockito.Mockito.mock(Metamodel.class);
            org.mockito.Mockito.when(metamodel.getEntities()).thenReturn(Set.of());
            org.mockito.Mockito.when(metamodel.getManagedTypes()).thenReturn(Set.of());
            org.mockito.Mockito.when(metamodel.getEmbeddables()).thenReturn(Set.of());

            EntityManagerFactory emf = org.mockito.Mockito.mock(EntityManagerFactory.class);
            org.mockito.Mockito.when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(Instant.now());
    }

    // ----------------------------------------------------------------
    // Mocked beans required by AdminSecurityConfig + TenantAdminController
    // ----------------------------------------------------------------

    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean java.time.Clock clock;
    @MockBean com.crosscert.passkey.admin.tenant.TenantAdminService tenantAdminService;
    @MockBean com.crosscert.passkey.admin.tenant.WebauthnDiffService webauthnDiffService;

    // JPA repos required by @EnableJpaRepositories scanning (full list from core)
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.MdsBlobCacheRepository mdsBlobCacheRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserInvitationRepository adminUserInvitationRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;

    // ----------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------

    /**
     * DEAD 상태에서 일반 admin API 는 503 으로 차단되어야 한다.
     * @WithMockUser 로 인증을 우회 — 필터는 인증 전에 실행된다.
     */
    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
    void deadState_blocksAdminApi_with503() throws Exception {
        mvc.perform(get("/admin/api/tenants"))
                .andExpect(status().isServiceUnavailable());
    }

    /**
     * DEAD 상태에서 /admin/api/license 는 통과해야 한다 (LicenseController L4.1 에서 추가).
     * LicenseController 가 없어서 지금은 404 가 반환되므로, exempt 경로 통과 여부만 확인.
     * L4.1 에서 enable.
     */
    @Test
    void deadState_allowsLicenseEndpoint() throws Exception {
        mvc.perform(get("/admin/api/license"))
                .andExpect(status().isOk());
    }

    /**
     * DEAD 상태에서 /actuator/health 는 통과해야 한다.
     * actuator 는 @WebMvcTest 슬라이스에서 포함되지 않으므로 404 로 exempt 통과를 확인.
     * 503 이 아니라면 필터가 exempt 경로를 올바르게 통과시키고 있는 것이다.
     */
    @Test
    void deadState_allowsHealthEndpoint_notBlocked() throws Exception {
        // @WebMvcTest does not wire actuator endpoints, so 404 is expected.
        // The important assertion is: NOT 503 (filter passes the request through).
        int status = mvc.perform(get("/actuator/health"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status)
                .as("DEAD state must not block exempt /actuator/health path with 503")
                .isNotEqualTo(503);
    }
}
