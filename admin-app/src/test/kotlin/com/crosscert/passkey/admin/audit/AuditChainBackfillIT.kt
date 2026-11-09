package com.crosscert.passkey.admin.audit

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.util.UUID
import javax.sql.DataSource

/**
 * Phase B Task 7 — backfill idempotency IT.
 *
 * Verifies that [AuditChainBackfillService.backfill] correctly:
 *  - Fills NULL tenant_hash / tenant_prev_hash columns (rowsUpdated >= 2).
 *  - Skips rows that already have a valid tenant chain (rowsSkipped >= 1).
 *  - Leaves the resulting tenant chain in a verifiable state (verifyAllTenants ok=true).
 *
 * Simulates the pre-V25 state by appending rows via [AuditLogService]
 * (which sets tenant_hash) and then NULLing those columns via native JDBC UPDATE.
 * A third row appended after the NULL-update retains its computed hashes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuditChainBackfillIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (identical pattern to AuditLogTenantScopingIT)
        // ----------------------------------------------------------------

        private const val ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart"
        private const val SYS_PASSWORD = "app_owner_pw"

        @Container
        @JvmField
        val ORACLE: OracleContainer = OracleContainer(ORACLE_IMAGE)
            .withUsername("PSK_APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("bootstrap-schema.sql"),
                "/tmp/bootstrap-schema.sql",
            )

        @Container
        @JvmField
        val REDIS: GenericContainer<*> = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(reg: DynamicPropertyRegistry) {
            val exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba " +
                    "@/tmp/bootstrap-schema.sql",
            )
            if (exec.exitCode != 0) {
                throw IllegalStateException(
                    "bootstrap-schema.sql failed (exit=" + exec.exitCode + ")\n" +
                        "STDOUT:\n" + exec.stdout + "\n" +
                        "STDERR:\n" + exec.stderr,
                )
            }
            reg.add("spring.datasource.url", ORACLE::getJdbcUrl)
            reg.add("spring.datasource.username") { "PSK_APP_ADMIN_USER" }
            reg.add("spring.datasource.password") { "admin_pw" }
            reg.add("spring.data.redis.host", REDIS::getHost)
            reg.add("spring.data.redis.port") { REDIS.getMappedPort(6379) }
        }

        // Fixed tenant UUID for reproducible FK inserts
        private val TENANT_A_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000A2")
        private val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

        /** PSK_APP_OWNER (schema owner) pool — used for owner-only table cleanup and tamper simulation. */
        private var ownerPool: HikariDataSource? = null

        @JvmStatic
        @AfterAll
        fun closeOwnerPool() {
            ownerPool?.close()
            ownerPool = null
        }

        @Synchronized
        private fun ownerJdbc(): JdbcTemplate {
            val pool = ownerPool ?: HikariDataSource().apply {
                jdbcUrl = ORACLE.jdbcUrl
                username = "PSK_APP_OWNER"
                password = SYS_PASSWORD
                maximumPoolSize = 2
                poolName = "audit-backfill-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
        }

        /** Convert UUID to 32-char hex (no hyphens) for Oracle HEXTORAW binding. */
        private fun uuidToHex(uuid: UUID): String = uuid.toString().replace("-", "").uppercase()
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @Autowired
    lateinit var auditLogService: AuditLogService

    @Autowired
    lateinit var verifier: AuditChainVerifier

    @Autowired
    lateinit var backfillService: AuditChainBackfillService

    @Autowired
    lateinit var ds: DataSource

    @Autowired
    lateinit var redisFactory: RedisConnectionFactory

    lateinit var jdbc: JdbcTemplate

    // ----------------------------------------------------------------
    // State reset — clean audit_log + seed one tenant
    // ----------------------------------------------------------------

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)

        // FK-safe delete order
        // audit_log: PSK_APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key")
        jdbc.update("DELETE FROM PSK_APP_OWNER.credential")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format")
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant")
        jdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant")

        // Seed tenant A
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                require_user_verification, mds_required, created_at, updated_at)
            VALUES (HEXTORAW(?), 'tenant-a-bf', 'Tenant A BF', 'localhost', 'Tenant A BF',
                'active', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)
            """.trimIndent(),
            uuidToHex(TENANT_A_ID),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
            VALUES (SYS_GUID(), HEXTORAW(?), 'http://localhost:9090', 0)
            """.trimIndent(),
            uuidToHex(TENANT_A_ID),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format)
            VALUES (SYS_GUID(), HEXTORAW(?), 'none')
            """.trimIndent(),
            uuidToHex(TENANT_A_ID),
        )

        // Flush Redis session data
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    // ----------------------------------------------------------------
    // Test — backfill fills NULLed tenant chain columns idempotently
    // ----------------------------------------------------------------

    @Test
    fun backfillFillsNullTenantChainColumns_andResultVerifies() {
        // ── 1. Append 3 rows (all get tenant_hash set by AuditLogService) ─
        //    Append ALL rows first so the 3rd row's tenant chain is linked
        //    to the 2nd row's valid tenant_hash at append time.
        val row1 = checkNotNull(
            auditLogService.append(
                AuditAppendRequest(
                    ACTOR_ID, "alice@example.com", "TENANT_CREATE",
                    "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, mapOf("seq" to 1),
                ),
            ),
        )
        val row2 = checkNotNull(
            auditLogService.append(
                AuditAppendRequest(
                    ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                    "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, mapOf("seq" to 2),
                ),
            ),
        )
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, mapOf("seq" to 3),
            ),
        )

        // ── 2. NULL out tenant_hash + tenant_prev_hash only for the first 2 rows ─
        //    Simulates a pre-V25 migration state where these columns were added
        //    after some rows already existed. Row 3 keeps its valid chain.
        //    audit_log UPDATE requires PSK_APP_OWNER (V10 withholds UPDATE from PSK_APP_ADMIN).
        val nulled = ownerJdbc().update(
            "UPDATE PSK_APP_OWNER.audit_log SET tenant_hash = NULL, tenant_prev_hash = NULL" +
                " WHERE id IN (HEXTORAW(?), HEXTORAW(?))",
            uuidToHex(row1.id), uuidToHex(row2.id),
        )
        assertThat(nulled).`as`("two rows should have been NULLed").isEqualTo(2)

        // ── 3. Run backfill ───────────────────────────────────────────────
        val summary = backfillService.backfill()

        // Rows 1 + 2 are NULLed → must be updated.
        // Row 3: its stored tenantPrevHash equals row2's hash computed *at append time*.
        // After backfill recomputes row2's hash, it matches row3's tenantPrevHash
        // (same algorithm, same inputs) so row3 satisfies the skip condition.
        assertThat(summary.rowsUpdated)
            .`as`("backfill should update at least the 2 NULLed rows: summary=%s", summary)
            .isGreaterThanOrEqualTo(2)
        assertThat(summary.rowsSkipped)
            .`as`("backfill should skip at least the 1 already-valid row: summary=%s", summary)
            .isGreaterThanOrEqualTo(1)

        // ── 6. All tenant chains should verify after backfill ─────────────
        val results = verifier.verifyAllTenants()
        assertThat(results).`as`("verifyAllTenants should return at least one result").isNotEmpty()
        for (r in results) {
            assertThat(r.ok)
                .`as`(
                    "tenant=%s chain should verify ok after backfill: brokenAt=%s",
                    r.tenantId, r.brokenAt,
                )
                .isTrue()
        }
    }
}
