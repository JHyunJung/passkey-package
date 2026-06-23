package com.crosscert.passkey.core.tenant;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3 acceptance gate. Verifies that {@link TenantFilterAspect} automatically
 * enables Hibernate's {@code tenantFilter} inside {@code @Transactional} boundaries
 * whenever {@link TenantContextHolder} carries a non-null tenant UUID.
 *
 * <p>The isolation scenario is:
 * <ol>
 *   <li>Seed two tenants (T_A, T_B) and one {@link Credential} per tenant via a
 *       direct admin connection (filter disabled, APP_ADMIN_USER).</li>
 *   <li>Set {@link TenantContextHolder} to T_A BEFORE entering the transactional
 *       boundary — the aspect fires at {@code @Transactional} entry and reads from
 *       the context at that point.</li>
 *   <li>Invoke a {@code @Transactional} service method ({@link CredentialQueryService})
 *       that calls {@code CredentialRepository.findAll()} — with the filter enabled
 *       this must return exactly T_A's credential.</li>
 *   <li>With context cleared (null) the same method must return ALL credentials
 *       (aspect skips filter enablement when context is null, matching the
 *       PLATFORM_OPERATOR / cross-tenant admin use-case).</li>
 * </ol>
 *
 * <p>The test FAILS without {@link TenantFilterAspect} because step 3 returns both
 * rows (filter never enabled). It PASSES once the aspect is active.
 *
 * <p>Uses the same Testcontainers Oracle XE 21 / bootstrap-schema.sql / application-test.yml
 * pattern as {@link VpdIsolationIT} and {@link TenantFilterBindingIT} for consistency.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TenantFilterAspectIT {

    // ── Minimal Spring Boot test app ────────────────────────────────────────
    @SpringBootApplication
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    static class TestApp {

        @Configuration
        static class ServiceConfig {
            @Bean
            CredentialQueryService credentialQueryService(CredentialRepository repo) {
                return new CredentialQueryService(repo);
            }
        }
    }

    // ── Testcontainers Oracle XE ─────────────────────────────────────────────
    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE =
            new OracleContainer(ORACLE_IMAGE)
                    .withUsername("APP_OWNER")
                    .withPassword(SYS_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("bootstrap-schema.sql"),
                            "/tmp/bootstrap-schema.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-schema.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-schema.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        // Finding #3 (Approach A): Flyway runs as APP_OWNER (schema owner)
        // so that migrations can GRANT on APP_OWNER objects without error.
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
    }

    // ── Test dependencies ────────────────────────────────────────────────────
    @Autowired
    CredentialRepository credentialRepository;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    CredentialQueryService credentialQueryService;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
    }

    // ── Helper: seed two tenants + one credential each (filter OFF) ──────────
    /** Returns [tenantAId, tenantBId] */
    private UUID[] seedTwoTenants() {
        // Seed directly without TenantContextHolder so filter is never enabled
        // (APP_ADMIN_USER is EXEMPT ACCESS POLICY — bypass VPD, direct JPA save works).
        TenantContextHolder.clear();

        Tenant tA = new Tenant("aspect-it-a", "Tenant A (Aspect IT)", "localhost", "Aspect RP A");
        tA.addAllowedOrigin("http://localhost", 0);
        tA.addAcceptedFormat("none");
        tA = tenantRepository.save(tA);
        final UUID idA = tA.getId();

        Tenant tB = new Tenant("aspect-it-b", "Tenant B (Aspect IT)", "localhost", "Aspect RP B");
        tB.addAllowedOrigin("http://localhost", 0);
        tB.addAcceptedFormat("none");
        tB = tenantRepository.save(tB);
        final UUID idB = tB.getId();

        credentialRepository.save(new Credential(idA, "uA".getBytes(), "cA".getBytes(), "pkA".getBytes(), null));
        credentialRepository.save(new Credential(idB, "uB".getBytes(), "cB".getBytes(), "pkB".getBytes(), null));

        return new UUID[]{idA, idB};
    }

    /**
     * Core isolation proof. With context = T_A, the aspect must enable
     * {@code tenantFilter(tenantId=T_A)} at @Transactional entry so that
     * {@code findAll()} inside the transaction sees only T_A's credential.
     *
     * <p>Without the aspect the filter is never enabled and findAll() returns
     * both rows — that is the expected FAIL before Task 3 lands.
     */
    @Test
    void aspectEnablesFilter_isolatesTenantACredentials() {
        UUID[] ids = seedTwoTenants();
        UUID idA = ids[0];
        UUID idB = ids[1];

        // Set context BEFORE the @Transactional boundary so the aspect
        // can read it when the transaction opens.
        TenantContextHolder.set(idA);

        // Invoke a @Transactional service method — the aspect fires at entry.
        List<Credential> visible = credentialQueryService.findAll();

        assertThat(visible)
                .as("aspect ON (context=A): only T_A's credential must be visible")
                .hasSize(1)
                .allSatisfy(c -> assertThat(c.getTenantId())
                        .as("every returned credential must belong to tenant A")
                        .isEqualTo(idA));
        assertThat(visible)
                .as("T_B's credential must NOT leak through the aspect-enabled filter")
                .noneMatch(c -> idB.equals(c.getTenantId()));
    }

    /**
     * Complementary check: null context → aspect skips filter enablement
     * → cross-tenant admin view returns all rows (PLATFORM_OPERATOR use-case).
     *
     * <p>This proves the aspect does not break the admin bypass path and that
     * the result in the previous test is caused by the filter, not by seeding
     * or DB state issues.
     */
    @Test
    void noContext_skipsFilter_allRowsVisible() {
        UUID[] ids = seedTwoTenants();
        UUID idA = ids[0];
        UUID idB = ids[1];

        // Explicitly clear to confirm null-context path is a no-op in the aspect.
        TenantContextHolder.clear();

        List<Credential> all = credentialQueryService.findAll();

        assertThat(all)
                .as("null context: aspect must NOT enable filter → both tenants visible")
                .hasSize(2)
                .extracting(Credential::getTenantId)
                .containsExactlyInAnyOrder(idA, idB);
    }

    // ── Thin @Transactional service used as the AOP join point ───────────────
    /**
     * Minimal {@code @Transactional} service bean. Its only purpose is to
     * provide an AOP-proxied join point so {@link TenantFilterAspect} fires.
     * Calling {@link CredentialRepository} directly from the test would not
     * trigger the aspect because the test class itself is not a Spring-managed
     * bean (its repository calls are not proxied through a @Transactional boundary
     * that the aspect can intercept at the right layer).
     */
    @Service
    static class CredentialQueryService {

        private final CredentialRepository repo;

        CredentialQueryService(CredentialRepository repo) {
            this.repo = repo;
        }

        @Transactional(readOnly = true)
        public List<Credential> findAll() {
            return repo.findAll();
        }
    }
}
