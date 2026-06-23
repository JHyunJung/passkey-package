package com.crosscert.passkey.admin;

import com.crosscert.passkey.admin.keymgmt.KeyExpirationJob;
import com.crosscert.passkey.admin.keymgmt.KeyRotationService;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.jwt.JwksAssembler;
import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 acceptance gate #2 (T24).
 *
 * <p>End-to-end key rotation flow against real Testcontainers Oracle XE + Redis.
 * Three scenarios:
 * <ol>
 *   <li>rotateAddsNewActiveAndKeepsOldAsRotated — happy-path rotation, JWKS
 *       and JWT verification after rotation.</li>
 *   <li>expirationJobRevokesAfterGrace — ROTATED key is revoked once grace
 *       elapses; JWKS stops publishing it.</li>
 *   <li>rotateRejectsConcurrentWithConflict — pre-INSERTed lease causes
 *       rotate() to throw BusinessException(KEY_ROTATION_CONFLICT).</li>
 * </ol>
 *
 * <p>Both @Scheduled jobs (MdsSyncJob + KeyExpirationJob) have their
 * initial-delay pushed to PT99H so they never fire automatically and
 * corrupt test state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
        "passkey.mds.initial-delay=PT99H",
        "passkey.mds.fixed-delay=PT99H",
        "passkey.key-rotation.expiration-job.initial-delay=PT99H",
        "passkey.key-rotation.expiration-job.fixed-delay=PT99H"
})
class KeyRotationIT {

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

    @Autowired SigningKeyRepository repo;
    @Autowired SigningKeyProvider provider;
    @Autowired KeyRotationService rotation;
    @Autowired KeyExpirationJob expirationJob;
    @Autowired JwksAssembler jwks;
    @Autowired AuditLogRepository auditRepo;
    @Autowired DataSource ds;

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
            ds.setPoolName("key-rotation-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        // Clear audit rows from previous test runs.
        // audit_log: APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        // Wipe all signing keys so provider.init() starts fresh.
        // signing_key: APP_ADMIN has no DELETE grant — use schema-owner pool.
        ownerJdbc().update("DELETE FROM APP_OWNER.signing_key");
        // Clear rotation and expiration lease rows but preserve the
        // AUDIT_CHAIN_LOCK sentinel row seeded by V14. Deleting it causes
        // AuditLogService to throw NoResultException on FOR UPDATE.
        jdbc.update("DELETE FROM APP_OWNER.scheduler_lease WHERE name IN ('key-rotation', 'key-expiration')");
        // Re-create the single ACTIVE signing key via the @PostConstruct path.
        provider.init();
    }

    // ----------------------------------------------------------------
    // Scenario 1: happy-path rotation
    // ----------------------------------------------------------------

    @Test
    void rotateAddsNewActiveAndKeepsOldAsRotated() throws Exception {
        RSAKey oldKey = provider.signingKey();
        String oldKid = oldKey.getKeyID();
        SignedJWT oldSigned = signJwt(oldKey, "old-token");

        KeyRotationService.RotateResult result = rotation.rotate(null, "(test)");
        assertThat(result.oldKid()).isEqualTo(oldKid);
        assertThat(result.newKid()).isNotEqualTo(oldKid);

        // DB has exactly 2 rows; old kid is ROTATED.
        assertThat(repo.findAll()).hasSize(2);
        SigningKey rotated = repo.findAll().stream()
                .filter(k -> oldKid.equals(k.getKid()))
                .findFirst()
                .orElseThrow();
        assertThat(rotated.getStatus()).isEqualTo("ROTATED");
        assertThat(rotated.getRotatedAt()).isNotNull();

        // JWKS exposes both keys; old JWT verifies against still-published old key.
        JWKSet set = jwks.build();
        assertThat(set.getKeys()).hasSize(2);
        RSAKey oldPub = (RSAKey) set.getKeyByKeyId(oldKid);
        assertThat(oldPub).isNotNull();
        assertThat(oldSigned.verify(new RSASSAVerifier(oldPub))).isTrue();

        // Audit trail has a SIGNING_KEY_ROTATE row.
        assertThat(auditRepo.findAllOrdered())
                .anyMatch(a -> "SIGNING_KEY_ROTATE".equals(a.getAction()));
    }

    // ----------------------------------------------------------------
    // Scenario 2: expiration job revokes after grace
    // ----------------------------------------------------------------

    @Test
    void expirationJobRevokesAfterGrace() throws Exception {
        rotation.rotate(null, "(test)");

        // Find the ROTATED key and back-date its rotated_at by 31 minutes
        // (beyond the default PT30M grace).
        SigningKey rotated = repo.findAll().stream()
                .filter(k -> "ROTATED".equals(k.getStatus()))
                .findFirst()
                .orElseThrow();

        Instant past = Instant.now().minus(Duration.ofMinutes(31));
        UUID rotatedId = rotated.getId();
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(rotatedId.getMostSignificantBits());
        bb.putLong(rotatedId.getLeastSignificantBits());
        jdbc.update("UPDATE APP_OWNER.signing_key SET rotated_at=? WHERE id=?",
                Timestamp.from(past), bb.array());

        expirationJob.runOnce();

        SigningKey fresh = repo.findById(rotated.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo("REVOKED");
        assertThat(fresh.getRevokedAt()).isNotNull();

        // JWKS must no longer publish the revoked key.
        JWKSet set = jwks.build();
        assertThat(set.getKeyByKeyId(rotated.getKid())).isNull();
    }

    // ----------------------------------------------------------------
    // Scenario 3: concurrent rotation rejected
    // ----------------------------------------------------------------

    @Test
    void rotateRejectsConcurrentWithConflict() {
        // Pre-INSERT a 'key-rotation' lease held by somebody-else (5-min TTL,
        // well beyond the 30s SchedulerLeaseService threshold).
        jdbc.update(
                "INSERT INTO APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                "VALUES ('key-rotation', 'somebody-else', SYSTIMESTAMP + INTERVAL '5' MINUTE)");

        assertThatThrownBy(() -> rotation.rotate(null, "(test)"))
                .isInstanceOf(com.crosscert.passkey.core.api.BusinessException.class)
                .extracting(e -> ((com.crosscert.passkey.core.api.BusinessException) e).getErrorCode())
                .isEqualTo(com.crosscert.passkey.core.api.ErrorCode.KEY_ROTATION_CONFLICT);
    }

    // ----------------------------------------------------------------
    // Helper
    // ----------------------------------------------------------------

    private static SignedJWT signJwt(RSAKey key, String subject) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .subject(subject)
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                        .build());
        jwt.sign(new RSASSASigner(key.toPrivateKey()));
        return jwt;
    }
}
