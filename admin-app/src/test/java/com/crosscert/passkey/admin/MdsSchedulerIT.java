package com.crosscert.passkey.admin;

import com.crosscert.passkey.admin.mds.MdsBlobClient;
import com.crosscert.passkey.admin.mds.MdsSchedulerService;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.metadata.data.MetadataBLOB;
import com.webauthn4j.metadata.data.MetadataBLOBPayload;
import com.webauthn4j.metadata.data.MetadataBLOBPayloadEntry;
import com.webauthn4j.metadata.data.toc.AuthenticatorStatus;
import com.webauthn4j.metadata.data.toc.StatusReport;
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
import static org.mockito.Mockito.mock;
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
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) throws Exception {
        // Run bootstrap-vpd.sql before Hikari opens its first connection —
        // APP_ADMIN_USER must exist before Spring's Flyway / datasource init.
        var exec = ORACLE.execInContainer(
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

    // Mocked — eliminates the need for a real signed FIDO MDS3 JWT BLOB.
    @MockBean
    MdsBlobClient client;

    @Autowired MdsSchedulerService scheduler;
    @Autowired DataSource ds;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory redisFactory;
    @Autowired AuditLogRepository auditRepo;

    JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        // Clear any audit rows from previous test runs.
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
        // Reset the mds_blob_cache sentinel row to its V17 seed values,
        // including next_update so that the happy-path test's 2099-01-01
        // write does not bleed into later tests.
        jdbc.update("UPDATE APP_OWNER.mds_blob_cache " +
                    "SET version=0, next_update=DATE '1970-01-01', blob_jwt='{}', " +
                    "    fetched_at=TIMESTAMP '1970-01-01 00:00:00 +00:00' " +
                    "WHERE id=1");
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

        MetadataBLOB blob = fakeBlob(42, LocalDate.of(2099, 1, 1),
                List.of(
                        entry(packedUuid,  List.of(AuthenticatorStatus.FIDO_CERTIFIED_L1)),
                        entry(revokedUuid, List.of(AuthenticatorStatus.FIDO_CERTIFIED_L1,
                                                   AuthenticatorStatus.REVOKED))
                ));
        when(client.fetch()).thenReturn(blob);

        MdsSchedulerService.SyncResult result = scheduler.runOnce();

        // ── status ──────────────────────────────────────────────────
        assertThat(result.status()).isEqualTo("SYNCED");
        assertThat(result.version()).isEqualTo(42L);
        assertThat(result.error()).isNull();

        // ── DB: version updated in sentinel row ──────────────────────
        Long version = jdbc.queryForObject(
                "SELECT version FROM APP_OWNER.mds_blob_cache WHERE id=1", Long.class);
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

    /**
     * Builds a fake {@link MetadataBLOBPayloadEntry} for the given AAGUID
     * and authenticator statuses. Adapted to the actual 0.31.5.RELEASE API:
     * <ul>
     *   <li>AuthenticatorStatus is an enum — use constants directly.</li>
     *   <li>StatusReport 8-arg constructor: (status, effectiveDate, certificate,
     *       url, certDesc, certNumber, certPolicyVer, certReqVer).</li>
     *   <li>MetadataBLOBPayloadEntry 9-arg constructor: (aaid, aaguid,
     *       attestationCertKeyIds, metadataStatement, biometricStatusReports,
     *       statusReports, timeOfLastStatusChange, rogueListURL, rogueListHash).</li>
     * </ul>
     */
    private static MetadataBLOBPayloadEntry entry(UUID aaguid,
                                                   List<AuthenticatorStatus> statuses) {
        List<StatusReport> reports = statuses.stream()
                .map(s -> new StatusReport(
                        s,
                        LocalDate.of(2026, 1, 1),
                        null,   // certificate
                        null,   // url
                        null,   // certificationDescriptor
                        null,   // certificateNumber
                        null,   // certificationPolicyVersion
                        null))  // certificationRequirementsVersion
                .toList();

        return new MetadataBLOBPayloadEntry(
                null,                  // aaid (FIDO U2F — null for FIDO2)
                new AAGUID(aaguid),    // aaguid
                null,                  // attestationCertificateKeyIdentifiers
                null,                  // metadataStatement
                null,                  // biometricStatusReports
                reports,               // statusReports
                LocalDate.of(2026, 1, 1), // timeOfLastStatusChange
                null,                  // rogueListURL
                null);                 // rogueListHash
    }

    /**
     * Builds a fake {@link MetadataBLOB} backed by Mockito stubs.
     *
     * <p>The real {@link MetadataBLOB} constructor requires a
     * {@link com.webauthn4j.data.jws.JWS} whose own constructor is
     * package-private. Using {@code mock()} lets us bypass that while
     * still exercising the code paths that call
     * {@code blob.getPayload().getNo()} and
     * {@code blob.getPayload().getEntries()}.
     *
     * <p>Note: MetadataBLOBPayload.no is declared as {@code Integer} in
     * 0.31.5.RELEASE; {@code getNo()} returns {@code Integer}. Passing
     * {@code int} here keeps it idiomatic.
     */
    private static MetadataBLOB fakeBlob(int version, LocalDate nextUpdate,
                                          List<MetadataBLOBPayloadEntry> entries) {
        MetadataBLOBPayload payload = new MetadataBLOBPayload(
                "test-header",
                version,
                nextUpdate,
                entries);

        MetadataBLOB blob = mock(MetadataBLOB.class);
        when(blob.getPayload()).thenReturn(payload);
        return blob;
    }
}
