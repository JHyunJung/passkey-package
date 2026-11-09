package com.crosscert.passkey.admin

import com.crosscert.passkey.admin.mds.MdsBlobClient
import com.crosscert.passkey.admin.mds.MdsSchedulerService
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.webauthn.mds.MdsBlob
import com.crosscert.passkey.webauthn.mds.MdsBlobEntry
import com.crosscert.passkey.webauthn.mds.MdsStatusReport
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Phase 3 acceptance gate #1 (T23).
 *
 * Exercises the full MDS scheduler chain against a real Testcontainers
 * Oracle XE + Redis. [MdsBlobClient] is mocked so no signed JWT BLOB
 * is required — only the storage + cache side runs against real containers.
 *
 * 3 scenarios:
 *  1. SYNCED — happy path: client returns a hand-built MetadataBLOB,
 *     DB row updated, Redis AAGUID entries written, audit row appended.
 *  2. SKIPPED — another instance holds the lease (pre-INSERTed row).
 *  3. FAILED — client.fetch() throws; scheduler returns FAILED with the
 *     error message preserved.
 *
 * MdsSyncJob's @Scheduled initial-delay is pushed to PT99H so it does
 * not fire automatically and corrupt test state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(
    properties = [
        "passkey.mds.initial-delay=PT99H",
        "passkey.mds.fixed-delay=PT99H",
    ],
)
class MdsSchedulerIT {

    companion object {
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
        val REDIS: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            // Run bootstrap-schema.sql before Hikari opens its first connection —
            // PSK_APP_ADMIN_USER must exist before Spring's Flyway / datasource init.
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

        /** PSK_APP_OWNER (schema owner) pool — used only for owner-only table cleanup in resetState(). */
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
                poolName = "mds-scheduler-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
        }

        // ----------------------------------------------------------------
        // Helpers
        // ----------------------------------------------------------------

        /** UUID → 16-byte big-endian (matches MdsAaguidCache.canonicalAaguid inverse). */
        private fun uuidBytes(u: UUID): ByteArray {
            val bb = ByteBuffer.allocate(16)
            bb.putLong(u.mostSignificantBits)
            bb.putLong(u.leastSignificantBits)
            return bb.array()
        }

        /**
         * Builds a native [MdsBlobEntry] for the given AAGUID and raw
         * status tokens. statusReports preserve MDS3 order (most recent last).
         */
        private fun entry(aaguid: UUID, statuses: List<String>): MdsBlobEntry {
            val reports = statuses.stream()
                .map { s -> MdsStatusReport(s, null) }
                .toList()
            return MdsBlobEntry(uuidBytes(aaguid), reports)
        }

        /** Builds the [MdsBlobClient.FetchResult] the mocked client returns. */
        private fun fakeFetch(
            version: Int,
            nextUpdate: LocalDate,
            entries: List<MdsBlobEntry>,
        ): MdsBlobClient.FetchResult = MdsBlobClient.FetchResult(
            "the.raw.jwt",
            MdsBlob(version, nextUpdate, entries),
        )
    }

    // Mocked — eliminates the need for a real signed FIDO MDS3 JWT BLOB.
    @MockitoBean
    lateinit var client: MdsBlobClient

    @Autowired
    lateinit var scheduler: MdsSchedulerService

    @Autowired
    lateinit var ds: DataSource

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var redisFactory: RedisConnectionFactory

    @Autowired
    lateinit var auditRepo: AuditLogRepository

    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)
        // Clear any audit rows from previous test runs.
        // audit_log: PSK_APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        // Reset the mds_blob_cache sentinel row to its V19 seed values,
        // including next_update so that the happy-path test's 2099-01-01
        // write does not bleed into later tests.
        jdbc.update(
            "UPDATE PSK_APP_OWNER.mds_blob_cache " +
                "SET version=0, next_update=DATE '1970-01-01', " +
                "    fetched_at=TIMESTAMP '1970-01-01 00:00:00 +00:00' " +
                "WHERE id=HEXTORAW('00000000000000000000000000000001')",
        )
        // Clear mds-sync lease rows but preserve the AUDIT_CHAIN_LOCK sentinel
        // row seeded by V14. Deleting AUDIT_CHAIN_LOCK causes AuditLogService
        // to throw NoResultException when it does FOR UPDATE on that row.
        jdbc.update("DELETE FROM PSK_APP_OWNER.scheduler_lease WHERE name='mds-sync'")
        // Flush Redis so AAGUID entries from previous tests don't bleed through.
        val redisConn = redisFactory.connection
        try {
            redisConn.serverCommands().flushAll()
        } finally {
            redisConn.close()
        }
    }

    // ----------------------------------------------------------------
    // Scenario 1: SYNCED happy path
    // ----------------------------------------------------------------

    @Test
    fun runOnceFetchesStoresAndPopulatesAaguidCache() {
        val packedUuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val revokedUuid = UUID.fromString("99999999-8888-7777-6666-555555555555")

        val fetched = fakeFetch(
            42, LocalDate.of(2099, 1, 1),
            listOf(
                entry(packedUuid, listOf("FIDO_CERTIFIED_L1")),
                entry(revokedUuid, listOf("FIDO_CERTIFIED_L1", "REVOKED")),
            ),
        )
        whenever(client.fetch()).thenReturn(fetched)

        val result = scheduler.runOnce()

        // ── status ──────────────────────────────────────────────────
        assertThat(result.status).isEqualTo("SYNCED")
        assertThat(result.version).isEqualTo(42L)
        assertThat(result.error).isNull()

        // ── DB: version updated in sentinel row ──────────────────────
        val version: Long? = jdbc.queryForObject(
            "SELECT version FROM PSK_APP_OWNER.mds_blob_cache " +
                "WHERE id=HEXTORAW('00000000000000000000000000000001')",
            Long::class.javaObjectType,
        )
        assertThat(version).isEqualTo(42L)

        // ── Redis: per-AAGUID CSV entries written ────────────────────
        // MdsSchedulerService writes "mds:aaguid:<UUID.toString()>"
        // where UUID.toString() is lowercase hyphenated canonical form.
        val packed = redis.opsForValue().get("mds:aaguid:$packedUuid")
        val revoked = redis.opsForValue().get("mds:aaguid:$revokedUuid")
        assertThat(packed).contains("FIDO_CERTIFIED_L1")
        assertThat(revoked).contains("FIDO_CERTIFIED_L1")
        assertThat(revoked).contains("REVOKED")

        // ── Audit: MDS_BLOB_SYNC row appended ────────────────────────
        assertThat(auditRepo.findAllOrdered())
            .anyMatch { a -> "MDS_BLOB_SYNC" == a.action }
    }

    // ----------------------------------------------------------------
    // Scenario 2: SKIPPED when lease held by another instance
    // ----------------------------------------------------------------

    @Test
    fun runOnceSkipsWhenAnotherInstanceHoldsLease() {
        // Pre-INSERT a lease row held by "somebody-else" that has not expired.
        jdbc.update(
            "INSERT INTO PSK_APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                "VALUES ('mds-sync', 'somebody-else', SYSTIMESTAMP + INTERVAL '1' HOUR)",
        )

        // client.fetch() must never be called — if it is, this throws to catch the bug.
        whenever(client.fetch()).thenThrow(RuntimeException("should not be called"))

        val result = scheduler.runOnce()

        assertThat(result.status).isEqualTo("SKIPPED")
        assertThat(result.version).isNull()
        assertThat(result.error).isNull()
    }

    // ----------------------------------------------------------------
    // Scenario 3: FAILED when fetch throws
    // ----------------------------------------------------------------

    @Test
    fun runOnceReturnsFailedWhenFetchThrows() {
        whenever(client.fetch()).thenThrow(IllegalStateException("MDS fetch failed: 503"))

        val result = scheduler.runOnce()

        assertThat(result.status).isEqualTo("FAILED")
        assertThat(result.version).isNull()
        assertThat(result.error).contains("MDS fetch failed")
    }
}
