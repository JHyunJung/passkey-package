package com.crosscert.passkey.admin.audit;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B Task 7 — per-tenant chain isolation IT.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Each tenant's chain verifies independently (verifyTenant ok=true).</li>
 *   <li>Tampering a payload row in tenantA breaks tenantA's chain but NOT tenantB's.</li>
 *   <li>The global verify() correctly reports ok=false when any tenant is tampered.</li>
 * </ul>
 *
 * <p>Uses {@link AuditLogService#append} directly to create rows (no HTTP),
 * and native JDBC UPDATE to tamper the payload column.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuditChainPerTenantIT {

    // ----------------------------------------------------------------
    // Containers (identical pattern to AuditLogTenantScopingIT)
    // ----------------------------------------------------------------

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-vpd.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-vpd.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @Autowired AuditLogService auditLogService;
    @Autowired AuditChainVerifier verifier;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

    // Fixed tenant UUIDs for reproducible FK inserts
    private static final UUID TENANT_A_ID = UUID.fromString("0000000000000000000000000000AA01".replace(
            "0000000000000000000000000000AA01", "00000000-0000-0000-0000-0000000000A1"));
    private static final UUID TENANT_B_ID = UUID.fromString("00000000-0000-0000-0000-0000000000B1");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** APP_OWNER (schema owner) pool — used for owner-only table cleanup and tamper simulation. */
    private static HikariDataSource ownerPool;

    @AfterAll
    static void closeOwnerPool() {
        if (ownerPool != null) {
            ownerPool.close();
            ownerPool = null;
        }
    }

    private static synchronized JdbcTemplate ownerJdbc() {
        if (ownerPool == null) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(ORACLE.getJdbcUrl());
            ds.setUsername("APP_OWNER");
            ds.setPassword(SYS_PASSWORD);
            ds.setMaximumPoolSize(2);
            ds.setPoolName("audit-per-tenant-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    // ----------------------------------------------------------------
    // State reset — clean audit_log + seed two tenants
    // ----------------------------------------------------------------

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);

        // FK-safe delete order (mirrors AuditLogTenantScopingIT)
        // audit_log: APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL");
        jdbc.update("DELETE FROM APP_OWNER.tenant");

        // Seed tenant A
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                    require_user_verification, mds_required, created_at, updated_at)
                VALUES (HEXTORAW(?), 'tenant-a', 'Tenant A', 'localhost', 'Tenant A',
                    'active', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)
                """, uuidToHex(TENANT_A_ID));
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
                VALUES (SYS_GUID(), HEXTORAW(?), 'http://localhost:9090', 0)
                """, uuidToHex(TENANT_A_ID));
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_accepted_format (id, tenant_id, format)
                VALUES (SYS_GUID(), HEXTORAW(?), 'none')
                """, uuidToHex(TENANT_A_ID));

        // Seed tenant B
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                    require_user_verification, mds_required, created_at, updated_at)
                VALUES (HEXTORAW(?), 'tenant-b', 'Tenant B', 'localhost', 'Tenant B',
                    'active', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)
                """, uuidToHex(TENANT_B_ID));
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
                VALUES (SYS_GUID(), HEXTORAW(?), 'http://localhost:9090', 0)
                """, uuidToHex(TENANT_B_ID));
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_accepted_format (id, tenant_id, format)
                VALUES (SYS_GUID(), HEXTORAW(?), 'none')
                """, uuidToHex(TENANT_B_ID));

        // Flush Redis session data
        var conn = redisFactory.getConnection();
        try {
            conn.serverCommands().flushAll();
        } finally {
            conn.close();
        }
    }

    // ----------------------------------------------------------------
    // Test — per-tenant chain isolation + tamper detection
    // ----------------------------------------------------------------

    @Test
    void tenantChainsAreIsolated_andTamperBreaksOnlyAffectedTenant() {
        // ── 1. Append 3 rows for tenantA ──────────────────────────────
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_CREATE",
                "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, Map.of("seq", 1)));
        var rowA2 = auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, Map.of("seq", 2)));
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, Map.of("seq", 3)));

        // ── 2. Append 3 rows for tenantB ──────────────────────────────
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_CREATE",
                "TENANT", TENANT_B_ID.toString(), TENANT_B_ID, Map.of("seq", 1)));
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_B_ID.toString(), TENANT_B_ID, Map.of("seq", 2)));
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_B_ID.toString(), TENANT_B_ID, Map.of("seq", 3)));

        // ── 3. Both tenant chains should be valid before tampering ─────
        AuditChainVerifier.TenantResult resultA = verifier.verifyTenant(TENANT_A_ID);
        assertThat(resultA.ok())
                .as("tenantA chain should be intact before tampering: brokenAt=%s", resultA.brokenAt())
                .isTrue();

        AuditChainVerifier.TenantResult resultB = verifier.verifyTenant(TENANT_B_ID);
        assertThat(resultB.ok())
                .as("tenantB chain should be intact before tampering: brokenAt=%s", resultB.brokenAt())
                .isTrue();

        // Global chain should also be intact
        AuditChainVerifier.Result globalBefore = verifier.verify();
        assertThat(globalBefore.ok())
                .as("global chain should be intact before tampering: brokenAt=%s", globalBefore.brokenAt())
                .isTrue();

        // ── 4. Tamper tenantA's 2nd row payload ───────────────────────
        // audit_log UPDATE requires APP_OWNER (V10 withholds UPDATE from APP_ADMIN).
        UUID tamperTargetId = rowA2.getId();
        int updated = ownerJdbc().update(
                "UPDATE APP_OWNER.audit_log SET payload = '{\"tampered\":true}' WHERE id = HEXTORAW(?)",
                uuidToHex(tamperTargetId));
        assertThat(updated).as("tamper UPDATE should affect exactly 1 row").isEqualTo(1);

        // ── 5. TenantA chain is now broken ────────────────────────────
        AuditChainVerifier.TenantResult resultAAfter = verifier.verifyTenant(TENANT_A_ID);
        assertThat(resultAAfter.ok())
                .as("tenantA chain should be broken after payload tamper")
                .isFalse();
        assertThat(resultAAfter.brokenAt())
                .as("brokenAt should point to the tampered row")
                .isEqualTo(tamperTargetId);

        // ── 6. TenantB chain is still intact (isolation) ──────────────
        AuditChainVerifier.TenantResult resultBAfter = verifier.verifyTenant(TENANT_B_ID);
        assertThat(resultBAfter.ok())
                .as("tenantB chain must remain intact — tamper in tenantA must not cross tenant boundary")
                .isTrue();

        // ── 7. Global chain is also broken (reflects tampered row) ────
        AuditChainVerifier.Result globalAfter = verifier.verify();
        assertThat(globalAfter.ok())
                .as("global chain should be broken because the tampered row is in the global chain too")
                .isFalse();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** Convert UUID to 32-char hex (no hyphens) for Oracle HEXTORAW binding. */
    private static String uuidToHex(UUID uuid) {
        return uuid.toString().replace("-", "").toUpperCase();
    }
}
