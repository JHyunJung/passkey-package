package com.crosscert.passkey.admin;

import com.crosscert.passkey.admin.mds.MdsBlobClient;
import com.crosscert.passkey.admin.mds.MdsSchedulerService;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.webauthn.mds.MdsBlob;
import com.crosscert.passkey.webauthn.mds.MdsBlobEntry;
import com.crosscert.passkey.webauthn.mds.MdsStatusReport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Phase 3 acceptance gate #1 (T23).
 *
 * <p>Exercises the full MDS scheduler chain against a real Testcontainers
 * Oracle XE + Redis. {@link MdsBlobClient} is mocked so no signed JWT BLOB
 * is required — only the storage + cache side runs against real containers.
 *
 * <p>3 scenarios:
 * <ol>
 *   <li>SYNCED — happy path: client returns a hand-built MetadataBLOB,
 *       DB row updated, Redis AAGUID entries written, audit row appended.</li>
 *   <li>SKIPPED — another instance holds the lease (pre-INSERTed row).</li>
 *   <li>FAILED — client.fetch() throws; scheduler returns FAILED with the
 *       error message preserved.</li>
 * </ol>
 *
 * <p>MdsSyncJob's @Scheduled initial-delay is pushed to PT99H so it does
 * not fire automatically and corrupt test state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
        "passkey.mds.initial-delay=PT99H",
        "passkey.mds.fixed-delay=PT99H"
})
class MdsSchedulerIT {

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-schema.sql"),
                    "/tmp/bootstrap-schema.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) throws Exception {
        // Run bootstrap-schema.sql before Hikari opens its first connection —
        // APP_ADMIN_USER must exist before Spring's Flyway / datasource init.
        var exec = ORACLE.execInContainer(
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
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // Mocked — eliminates the need for a real signed FIDO MDS3 JWT BLOB.
    @MockBean
    MdsBlobClient client;

    @Autowired MdsSchedulerService scheduler;
    @Autowired DataSource ds;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory redisFactory;
    @Autowired AuditLogRepository auditRepo;

    JdbcTemplate jdbc;

    /** APP_OWNER (schema owner) pool — used only for owner-only table cleanup in resetState(). */
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
            ds.setPoolName("mds-scheduler-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        // Clear any audit rows from previous test runs.
        // audit_log: APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        // Reset the mds_blob_cache sentinel row to its V19 seed values,
        // including next_update so that the happy-path test's 2099-01-01
        // write does not bleed into later tests.
        jdbc.update("UPDATE APP_OWNER.mds_blob_cache " +
                    "SET version=0, next_update=DATE '1970-01-01', " +
                    "    fetched_at=TIMESTAMP '1970-01-01 00:00:00 +00:00' " +
                    "WHERE id=HEXTORAW('00000000000000000000000000000001')");
        // Clear mds-sync lease rows but preserve the AUDIT_CHAIN_LOCK sentinel
        // row seeded by V14. Deleting AUDIT_CHAIN_LOCK causes AuditLogService
        // to throw NoResultException when it does FOR UPDATE on that row.
        jdbc.update("DELETE FROM APP_OWNER.scheduler_lease WHERE name='mds-sync'");
        // Flush Redis so AAGUID entries from previous tests don't bleed through.
        var redisConn = redisFactory.getConnection();
        try {
            redisConn.serverCommands().flushAll();
        } finally {
            redisConn.close();
        }
    }

    // ----------------------------------------------------------------
    // Scenario 1: SYNCED happy path
    // ----------------------------------------------------------------

    @Test
    void runOnceFetchesStoresAndPopulatesAaguidCache() {
        UUID packedUuid  = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID revokedUuid = UUID.fromString("99999999-8888-7777-6666-555555555555");

        MdsBlobClient.FetchResult fetched = fakeFetch(42, LocalDate.of(2099, 1, 1),
                List.of(
                        entry(packedUuid,  List.of("FIDO_CERTIFIED_L1")),
                        entry(revokedUuid, List.of("FIDO_CERTIFIED_L1", "REVOKED"))
                ));
        when(client.fetch()).thenReturn(fetched);

        MdsSchedulerService.SyncResult result = scheduler.runOnce();

        // ── status ──────────────────────────────────────────────────
        assertThat(result.status()).isEqualTo("SYNCED");
        assertThat(result.version()).isEqualTo(42L);
        assertThat(result.error()).isNull();

        // ── DB: version updated in sentinel row ──────────────────────
        Long version = jdbc.queryForObject(
                "SELECT version FROM APP_OWNER.mds_blob_cache " +
                "WHERE id=HEXTORAW('00000000000000000000000000000001')", Long.class);
        assertThat(version).isEqualTo(42L);

        // ── Redis: per-AAGUID CSV entries written ────────────────────
        // MdsSchedulerService writes "mds:aaguid:<UUID.toString()>"
        // where UUID.toString() is lowercase hyphenated canonical form.
        String packed  = redis.opsForValue().get("mds:aaguid:" + packedUuid);
        String revoked = redis.opsForValue().get("mds:aaguid:" + revokedUuid);
        assertThat(packed).contains("FIDO_CERTIFIED_L1");
        assertThat(revoked).contains("FIDO_CERTIFIED_L1");
        assertThat(revoked).contains("REVOKED");

        // ── Audit: MDS_BLOB_SYNC row appended ────────────────────────
        assertThat(auditRepo.findAllOrdered())
                .anyMatch(a -> "MDS_BLOB_SYNC".equals(a.getAction()));
    }

    // ----------------------------------------------------------------
    // Scenario 2: SKIPPED when lease held by another instance
    // ----------------------------------------------------------------

    @Test
    void runOnceSkipsWhenAnotherInstanceHoldsLease() {
        // Pre-INSERT a lease row held by "somebody-else" that has not expired.
        jdbc.update(
                "INSERT INTO APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                "VALUES ('mds-sync', 'somebody-else', SYSTIMESTAMP + INTERVAL '1' HOUR)");

        // client.fetch() must never be called — if it is, this throws to catch the bug.
        when(client.fetch()).thenThrow(new RuntimeException("should not be called"));

        MdsSchedulerService.SyncResult result = scheduler.runOnce();

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.version()).isNull();
        assertThat(result.error()).isNull();
    }

    // ----------------------------------------------------------------
    // Scenario 3: FAILED when fetch throws
    // ----------------------------------------------------------------

    @Test
    void runOnceReturnsFailedWhenFetchThrows() {
        when(client.fetch()).thenThrow(new IllegalStateException("MDS fetch failed: 503"));

        MdsSchedulerService.SyncResult result = scheduler.runOnce();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.version()).isNull();
        assertThat(result.error()).contains("MDS fetch failed");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** UUID → 16-byte big-endian (matches MdsAaguidCache.canonicalAaguid inverse). */
    private static byte[] uuidBytes(UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    /**
     * Builds a native {@link MdsBlobEntry} for the given AAGUID and raw
     * status tokens. statusReports preserve MDS3 order (most recent last).
     */
    private static MdsBlobEntry entry(UUID aaguid, List<String> statuses) {
        List<MdsStatusReport> reports = statuses.stream()
                .map(s -> new MdsStatusReport(s, null))
                .toList();
        return new MdsBlobEntry(uuidBytes(aaguid), reports);
    }

    /** Builds the {@link MdsBlobClient.FetchResult} the mocked client returns. */
    private static MdsBlobClient.FetchResult fakeFetch(int version, LocalDate nextUpdate,
                                                       List<MdsBlobEntry> entries) {
        return new MdsBlobClient.FetchResult("the.raw.jwt",
                new MdsBlob(version, nextUpdate, entries));
    }
}
