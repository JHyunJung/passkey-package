package com.crosscert.passkey.admin.audit;

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
 * Phase B Task 7 — backfill idempotency IT.
 *
 * <p>Verifies that {@link AuditChainBackfillService#backfill()} correctly:
 * <ul>
 *   <li>Fills NULL tenant_hash / tenant_prev_hash columns (rowsUpdated >= 2).</li>
 *   <li>Skips rows that already have a valid tenant chain (rowsSkipped >= 1).</li>
 *   <li>Leaves the resulting tenant chain in a verifiable state (verifyAllTenants ok=true).</li>
 * </ul>
 *
 * <p>Simulates the pre-V25 state by appending rows via {@link AuditLogService}
 * (which sets tenant_hash) and then NULLing those columns via native JDBC UPDATE.
 * A third row appended after the NULL-update retains its computed hashes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuditChainBackfillIT {

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
    @Autowired AuditChainBackfillService backfillService;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

    // Fixed tenant UUID for reproducible FK inserts
    private static final UUID TENANT_A_ID = UUID.fromString("00000000-0000-0000-0000-0000000000A2");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // ----------------------------------------------------------------
    // State reset — clean audit_log + seed one tenant
    // ----------------------------------------------------------------

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);

        // FK-safe delete order
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
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
                VALUES (HEXTORAW(?), 'tenant-a-bf', 'Tenant A BF', 'localhost', 'Tenant A BF',
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

        // Flush Redis session data
        var conn = redisFactory.getConnection();
        try {
            conn.serverCommands().flushAll();
        } finally {
            conn.close();
        }
    }

    // ----------------------------------------------------------------
    // Test — backfill fills NULLed tenant chain columns idempotently
    // ----------------------------------------------------------------

    @Test
    void backfillFillsNullTenantChainColumns_andResultVerifies() {
        // ── 1. Append 3 rows (all get tenant_hash set by AuditLogService) ─
        //    Append ALL rows first so the 3rd row's tenant chain is linked
        //    to the 2nd row's valid tenant_hash at append time.
        var row1 = auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_CREATE",
                "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, Map.of("seq", 1)));
        var row2 = auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, Map.of("seq", 2)));
        var row3 = auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, Map.of("seq", 3)));

        // ── 2. NULL out tenant_hash + tenant_prev_hash only for the first 2 rows ─
        //    Simulates a pre-V25 migration state where these columns were added
        //    after some rows already existed. Row 3 keeps its valid chain.
        int nulled = jdbc.update(
                "UPDATE APP_OWNER.audit_log SET tenant_hash = NULL, tenant_prev_hash = NULL"
                        + " WHERE id IN (HEXTORAW(?), HEXTORAW(?))",
                uuidToHex(row1.getId()), uuidToHex(row2.getId()));
        assertThat(nulled).as("two rows should have been NULLed").isEqualTo(2);

        // ── 3. Run backfill ───────────────────────────────────────────────
        AuditChainBackfillService.Summary summary = backfillService.backfill();

        // Rows 1 + 2 are NULLed → must be updated.
        // Row 3: its stored tenantPrevHash equals row2's hash computed *at append time*.
        // After backfill recomputes row2's hash, it matches row3's tenantPrevHash
        // (same algorithm, same inputs) so row3 satisfies the skip condition.
        assertThat(summary.rowsUpdated())
                .as("backfill should update at least the 2 NULLed rows: summary=%s", summary)
                .isGreaterThanOrEqualTo(2);
        assertThat(summary.rowsSkipped())
                .as("backfill should skip at least the 1 already-valid row: summary=%s", summary)
                .isGreaterThanOrEqualTo(1);

        // ── 6. All tenant chains should verify after backfill ─────────────
        List<AuditChainVerifier.TenantResult> results = verifier.verifyAllTenants();
        assertThat(results).as("verifyAllTenants should return at least one result").isNotEmpty();
        for (AuditChainVerifier.TenantResult r : results) {
            assertThat(r.ok())
                    .as("tenant=%s chain should verify ok after backfill: brokenAt=%s",
                            r.tenantId(), r.brokenAt())
                    .isTrue();
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** Convert UUID to 32-char hex (no hyphens) for Oracle HEXTORAW binding. */
    private static String uuidToHex(UUID uuid) {
        return uuid.toString().replace("-", "").toUpperCase();
    }
}
