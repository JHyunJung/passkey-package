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
 * Phase B Task 7 — per-tenant chain isolation IT.
 *
 * Verifies that:
 *  - Each tenant's chain verifies independently (verifyTenant ok=true).
 *  - Tampering a payload row in tenantA breaks tenantA's chain but NOT tenantB's.
 *  - The global verify() correctly reports ok=false when any tenant is tampered.
 *
 * Uses [AuditLogService.append] directly to create rows (no HTTP),
 * and native JDBC UPDATE to tamper the payload column.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuditChainPerTenantIT {

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

        // Fixed tenant UUIDs for reproducible FK inserts
        private val TENANT_A_ID: UUID = UUID.fromString(
            "0000000000000000000000000000AA01".replace(
                "0000000000000000000000000000AA01", "00000000-0000-0000-0000-0000000000A1",
            ),
        )
        private val TENANT_B_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000B1")
        private val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

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
                poolName = "audit-per-tenant-it-owner"
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
    lateinit var ds: DataSource

    @Autowired
    lateinit var redisFactory: RedisConnectionFactory

    lateinit var jdbc: JdbcTemplate

    // ----------------------------------------------------------------
    // State reset — clean audit_log + seed two tenants
    // ----------------------------------------------------------------

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)

        // FK-safe delete order (mirrors AuditLogTenantScopingIT)
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
            VALUES (HEXTORAW(?), 'tenant-a', 'Tenant A', 'localhost', 'Tenant A',
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

        // Seed tenant B
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                require_user_verification, mds_required, created_at, updated_at)
            VALUES (HEXTORAW(?), 'tenant-b', 'Tenant B', 'localhost', 'Tenant B',
                'active', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)
            """.trimIndent(),
            uuidToHex(TENANT_B_ID),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
            VALUES (SYS_GUID(), HEXTORAW(?), 'http://localhost:9090', 0)
            """.trimIndent(),
            uuidToHex(TENANT_B_ID),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format)
            VALUES (SYS_GUID(), HEXTORAW(?), 'none')
            """.trimIndent(),
            uuidToHex(TENANT_B_ID),
        )

        // Flush Redis session data
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    // ----------------------------------------------------------------
    // Test — per-tenant chain isolation + tamper detection
    // ----------------------------------------------------------------

    @Test
    fun tenantChainsAreIsolated_andTamperBreaksOnlyAffectedTenant() {
        // ── 1. Append 3 rows for tenantA ──────────────────────────────
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_CREATE",
                "TENANT", TENANT_A_ID.toString(), TENANT_A_ID, mapOf("seq" to 1),
            ),
        )
        val rowA2 = checkNotNull(
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

        // ── 2. Append 3 rows for tenantB ──────────────────────────────
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_CREATE",
                "TENANT", TENANT_B_ID.toString(), TENANT_B_ID, mapOf("seq" to 1),
            ),
        )
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_B_ID.toString(), TENANT_B_ID, mapOf("seq" to 2),
            ),
        )
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, "alice@example.com", "TENANT_UPDATE",
                "TENANT", TENANT_B_ID.toString(), TENANT_B_ID, mapOf("seq" to 3),
            ),
        )

        // ── 3. Both tenant chains should be valid before tampering ─────
        val resultA = verifier.verifyTenant(TENANT_A_ID)
        assertThat(resultA.ok)
            .`as`("tenantA chain should be intact before tampering: brokenAt=%s", resultA.brokenAt)
            .isTrue()

        val resultB = verifier.verifyTenant(TENANT_B_ID)
        assertThat(resultB.ok)
            .`as`("tenantB chain should be intact before tampering: brokenAt=%s", resultB.brokenAt)
            .isTrue()

        // Global chain should also be intact
        val globalBefore = verifier.verify()
        assertThat(globalBefore.ok)
            .`as`("global chain should be intact before tampering: brokenAt=%s", globalBefore.brokenAt)
            .isTrue()

        // ── 4. Tamper tenantA's 2nd row payload ───────────────────────
        // audit_log UPDATE requires PSK_APP_OWNER (V10 withholds UPDATE from PSK_APP_ADMIN).
        val tamperTargetId = rowA2.id
        val updated = ownerJdbc().update(
            "UPDATE PSK_APP_OWNER.audit_log SET payload = '{\"tampered\":true}' WHERE id = HEXTORAW(?)",
            uuidToHex(tamperTargetId),
        )
        assertThat(updated).`as`("tamper UPDATE should affect exactly 1 row").isEqualTo(1)

        // ── 5. TenantA chain is now broken ────────────────────────────
        val resultAAfter = verifier.verifyTenant(TENANT_A_ID)
        assertThat(resultAAfter.ok)
            .`as`("tenantA chain should be broken after payload tamper")
            .isFalse()
        assertThat(resultAAfter.brokenAt)
            .`as`("brokenAt should point to the tampered row")
            .isEqualTo(tamperTargetId)

        // ── 6. TenantB chain is still intact (isolation) ──────────────
        val resultBAfter = verifier.verifyTenant(TENANT_B_ID)
        assertThat(resultBAfter.ok)
            .`as`("tenantB chain must remain intact — tamper in tenantA must not cross tenant boundary")
            .isTrue()

        // ── 7. Global chain is also broken (reflects tampered row) ────
        val globalAfter = verifier.verify()
        assertThat(globalAfter.ok)
            .`as`("global chain should be broken because the tampered row is in the global chain too")
            .isFalse()
    }
}
