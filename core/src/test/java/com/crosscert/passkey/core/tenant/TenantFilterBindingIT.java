package com.crosscert.passkey.core.tenant;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1 spike: verifies that a Hibernate {@code @Filter} with a UUID-typed
 * named parameter binds correctly to Oracle's {@code RAW(16)} column
 * ({@code CREDENTIAL.TENANT_ID}).
 *
 * <p>Boots a real Testcontainers Oracle XE 21 (same image/bootstrap as
 * {@link AppLevelIsolationIT}) so the Flyway schema is applied identically.
 * Isolation is enforced purely by the Hibernate filter enabled inside the
 * test — with Oracle VPD removed there is no DB-kernel layer.
 *
 * <p>Pass criterion: after enabling the filter with {@code tenantId = T_A},
 * querying {@code CredentialRepository.findAll()} must return only T_A's
 * credential — T_B's row must not appear.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class TenantFilterBindingIT {

    @SpringBootApplication
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    static class TestApp {
    }

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

    @Autowired
    EntityManager em;

    @Autowired
    CredentialRepository credentials;

    @Autowired
    TenantRepository tenants;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        // Clear admin_user_tenant mapping before deleting tenants (FK admin_user_tenant.tenant_id → tenant)
        jdbc.update("DELETE FROM APP_OWNER.admin_user_tenant");
        jdbc.update("UPDATE APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
    }

    /**
     * Verifies that enabling a Hibernate filter named {@code tenantFilter}
     * with a UUID-typed parameter binds correctly to Oracle {@code RAW(16)}.
     *
     * <p>Real Credential constructor: {@code (UUID tenantId, byte[] userHandle,
     * byte[] credentialId, byte[] publicKey, byte[] aaguid)}.
     *
     * <p>Step 2 (pre-filter addition): expect FAIL with
     * "no filter named tenantFilter" — proves the @FilterDef annotation
     * is required.
     *
     * <p>Step 4 (post-filter addition): expect PASS — proves UUID binding
     * works against RAW(16) without type mismatch.
     */
    @Test
    void filterBindsUuidToRaw16AndIsolatesTenant() {
        // Persist Tenant rows first — FK_CREDENTIAL_TENANT requires a parent row.
        Tenant tenantA = new Tenant("filter-spike-a", "Tenant Filter A", "localhost", "Filter Test RP A");
        tenantA.addAllowedOrigin("http://localhost", 0);
        tenantA.addAcceptedFormat("none");
        tenantA = tenants.save(tenantA);
        final UUID idA = tenantA.getId();

        Tenant tenantB = new Tenant("filter-spike-b", "Tenant Filter B", "localhost", "Filter Test RP B");
        tenantB.addAllowedOrigin("http://localhost", 0);
        tenantB.addAcceptedFormat("none");
        tenantB = tenants.save(tenantB);
        final UUID idB = tenantB.getId();

        // Real Credential constructor: (UUID tenantId, byte[] userHandle,
        // byte[] credentialId, byte[] publicKey, byte[] aaguid)
        credentials.save(new Credential(
                idA,
                "ua".getBytes(),
                "ca".getBytes(),
                "pk_a".getBytes(),
                null));
        credentials.save(new Credential(
                idB,
                "ub".getBytes(),
                "cb".getBytes(),
                "pk_b".getBytes(),
                null));
        em.flush();
        em.clear();

        // ── Control group: filter OFF → both tenants' credentials visible. ──
        // This proves the post-filter result below is caused by the filter,
        // not by a missing seed row or an already-empty table.
        var unfiltered = credentials.findAll();
        assertThat(unfiltered)
                .as("filter OFF: both T_A and T_B credentials must be visible")
                .hasSize(2)
                .extracting(Credential::getTenantId)
                .containsExactlyInAnyOrder(idA, idB);
        em.clear();

        // Enable Hibernate tenantFilter for tenant A.
        // The filter parameter type must match the @ParamDef on @FilterDef.
        em.unwrap(Session.class)
          .enableFilter("tenantFilter")
          .setParameter("tenantId", idA);
        em.clear();

        // ── Verification: filter ON (tenantId=A) → exactly T_A's row, and
        // only T_A's. This asserts BOTH halves of correct isolation:
        //   - positive: A is still visible (filter must not hide A), size == 1
        //   - negative: B is hidden (no cross-tenant leakage)
        var filtered = credentials.findAll();
        assertThat(filtered)
                .as("filter ON (tenantId=A): exactly one row, all belonging to T_A")
                .hasSize(1)
                .allSatisfy(c -> assertThat(c.getTenantId())
                        .as("every visible credential must belong to T_A")
                        .isEqualTo(idA));
        assertThat(filtered)
                .as("T_B's credential must NOT leak through the filter")
                .noneMatch(c -> idB.equals(c.getTenantId()));
    }
}
