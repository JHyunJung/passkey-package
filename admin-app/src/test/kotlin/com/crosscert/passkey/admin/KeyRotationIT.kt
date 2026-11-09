package com.crosscert.passkey.admin

import com.crosscert.passkey.admin.keymgmt.KeyExpirationJob
import com.crosscert.passkey.admin.keymgmt.KeyRotationService
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.jwt.JwksAssembler
import com.crosscert.passkey.core.jwt.SigningKeyProvider
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.SigningKeyRepository
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.sql.DataSource

/**
 * Phase 3 acceptance gate #2 (T24).
 *
 * End-to-end key rotation flow against real Testcontainers Oracle XE + Redis.
 * Three scenarios:
 *  1. rotateAddsNewActiveAndKeepsOldAsRotated — happy-path rotation, JWKS
 *     and JWT verification after rotation.
 *  2. expirationJobRevokesAfterGrace — ROTATED key is revoked once grace
 *     elapses; JWKS stops publishing it.
 *  3. rotateRejectsConcurrentWithConflict — pre-INSERTed lease causes
 *     rotate() to throw BusinessException(KEY_ROTATION_CONFLICT).
 *
 * Both @Scheduled jobs (MdsSyncJob + KeyExpirationJob) have their
 * initial-delay pushed to PT99H so they never fire automatically and
 * corrupt test state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(
    properties = [
        "passkey.mds.initial-delay=PT99H",
        "passkey.mds.fixed-delay=PT99H",
        "passkey.key-rotation.expiration-job.initial-delay=PT99H",
        "passkey.key-rotation.expiration-job.fixed-delay=PT99H",
    ],
)
class KeyRotationIT {

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
                poolName = "key-rotation-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
        }

        // ----------------------------------------------------------------
        // Helper
        // ----------------------------------------------------------------

        private fun signJwt(key: RSAKey, subject: String): SignedJWT {
            val jwt = SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(),
                JWTClaimsSet.Builder()
                    .subject(subject)
                    .issueTime(Date())
                    .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                    .build(),
            )
            jwt.sign(RSASSASigner(key.toPrivateKey()))
            return jwt
        }
    }

    @Autowired
    lateinit var repo: SigningKeyRepository

    @Autowired
    lateinit var provider: SigningKeyProvider

    @Autowired
    lateinit var rotation: KeyRotationService

    @Autowired
    lateinit var expirationJob: KeyExpirationJob

    @Autowired
    lateinit var jwks: JwksAssembler

    @Autowired
    lateinit var auditRepo: AuditLogRepository

    @Autowired
    lateinit var ds: DataSource

    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)
        // Clear audit rows from previous test runs.
        // audit_log: PSK_APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        // Wipe all signing keys so provider.init() starts fresh.
        // signing_key: PSK_APP_ADMIN has no DELETE grant — use schema-owner pool.
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.signing_key")
        // Clear rotation and expiration lease rows but preserve the
        // AUDIT_CHAIN_LOCK sentinel row seeded by V14. Deleting it causes
        // AuditLogService to throw NoResultException on FOR UPDATE.
        jdbc.update("DELETE FROM PSK_APP_OWNER.scheduler_lease WHERE name IN ('key-rotation', 'key-expiration')")
        // Re-create the single ACTIVE signing key via the @PostConstruct path.
        provider.init()
    }

    // ----------------------------------------------------------------
    // Scenario 1: happy-path rotation
    // ----------------------------------------------------------------

    @Test
    fun rotateAddsNewActiveAndKeepsOldAsRotated() {
        val oldKey = provider.signingKey()
        val oldKid = oldKey.keyID
        val oldSigned = signJwt(oldKey, "old-token")

        val result = rotation.rotate(null, "(test)")
        assertThat(result.oldKid).isEqualTo(oldKid)
        assertThat(result.newKid).isNotEqualTo(oldKid)

        // DB has exactly 2 rows; old kid is ROTATED.
        assertThat(repo.findAll()).hasSize(2)
        val rotated = repo.findAll().stream()
            .filter { k -> oldKid == k.kid }
            .findFirst()
            .orElseThrow()
        assertThat(rotated.status).isEqualTo("ROTATED")
        assertThat(rotated.rotatedAt).isNotNull()

        // JWKS exposes both keys; old JWT verifies against still-published old key.
        val set = jwks.build()
        assertThat(set.keys).hasSize(2)
        // 원 Java (RSAKey) 캐스트처럼 null 은 null 로 통과시켜 아래 isNotNull 이 판정하게 한다.
        val oldPub = set.getKeyByKeyId(oldKid) as RSAKey?
        assertThat(oldPub).isNotNull()
        assertThat(oldSigned.verify(RSASSAVerifier(checkNotNull(oldPub)))).isTrue()

        // Audit trail has a SIGNING_KEY_ROTATE row.
        assertThat(auditRepo.findAllOrdered())
            .anyMatch { a -> "SIGNING_KEY_ROTATE" == a.action }
    }

    // ----------------------------------------------------------------
    // Scenario 2: expiration job revokes after grace
    // ----------------------------------------------------------------

    @Test
    fun expirationJobRevokesAfterGrace() {
        rotation.rotate(null, "(test)")

        // Find the ROTATED key and back-date its rotated_at by 31 minutes
        // (beyond the default PT30M grace).
        val rotated = repo.findAll().stream()
            .filter { k -> "ROTATED" == k.status }
            .findFirst()
            .orElseThrow()

        val past = Instant.now().minus(Duration.ofMinutes(31))
        val rotatedId = rotated.id
        val bb = ByteBuffer.allocate(16)
        bb.putLong(rotatedId.mostSignificantBits)
        bb.putLong(rotatedId.leastSignificantBits)
        jdbc.update(
            "UPDATE PSK_APP_OWNER.signing_key SET rotated_at=? WHERE id=?",
            Timestamp.from(past), bb.array(),
        )

        expirationJob.runOnce()

        val fresh = repo.findById(rotated.id).orElseThrow()
        assertThat(fresh.status).isEqualTo("REVOKED")
        assertThat(fresh.revokedAt).isNotNull()

        // JWKS must no longer publish the revoked key.
        val set = jwks.build()
        assertThat(set.getKeyByKeyId(rotated.kid)).isNull()
    }

    // ----------------------------------------------------------------
    // Scenario 3: concurrent rotation rejected
    // ----------------------------------------------------------------

    @Test
    fun rotateRejectsConcurrentWithConflict() {
        // Pre-INSERT a 'key-rotation' lease held by somebody-else (5-min TTL,
        // well beyond the 30s SchedulerLeaseService threshold).
        jdbc.update(
            "INSERT INTO PSK_APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                "VALUES ('key-rotation', 'somebody-else', SYSTIMESTAMP + INTERVAL '5' MINUTE)",
        )

        assertThatThrownBy { rotation.rotate(null, "(test)") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.KEY_ROTATION_CONFLICT)
    }
}
