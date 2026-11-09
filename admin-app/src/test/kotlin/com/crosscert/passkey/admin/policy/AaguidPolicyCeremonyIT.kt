package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.tenant.TenantAdminService
import com.crosscert.passkey.core.entity.TenantAaguidPolicy
import com.crosscert.passkey.core.policy.AaguidPolicyViolationException
import com.crosscert.passkey.core.policy.DefaultAaguidPolicyChecker
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * Phase C Task 6 — AAGUID 정책 분기 통합 테스트.
 *
 * 시나리오:
 *  1. Tenant 생성 → mode=ANY 정책 자동 생성 검증
 *  2. ALLOWLIST 설정 → aaguidA 통과, aaguidB 차단
 *  3. DENYLIST 설정 → aaguidA 차단, aaguidB 통과
 *
 * [DefaultAaguidPolicyChecker] 를 직접 호출(서비스 레이어 bypass 없이)하여
 * 정책 분기 로직을 DB 연동 상태에서 검증한다. HTTP 레이어 없이 core 단위 통합 테스트.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AaguidPolicyCeremonyIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (identical pattern to AuditChainPerTenantIT)
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
        val REDIS: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

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

        private val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private const val ACTOR_EMAIL = "alice@crosscert.com"

        // Two test AAGUIDs
        private val AAGUID_A: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        private val AAGUID_B: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

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
                poolName = "aaguid-policy-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
        }

        // ----------------------------------------------------------------
        // Helpers
        // ----------------------------------------------------------------

        /** Convert UUID to 32-char hex (no hyphens) for Oracle HEXTORAW binding. */
        @Suppress("unused")
        private fun uuidToHex(uuid: UUID): String = uuid.toString().replace("-", "").uppercase()
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @Autowired
    lateinit var tenantAdminService: TenantAdminService

    @Autowired
    lateinit var aaguidPolicyService: AaguidPolicyService

    @Autowired
    lateinit var policyChecker: DefaultAaguidPolicyChecker

    @Autowired
    lateinit var policyRepo: TenantAaguidPolicyRepository

    @Autowired
    lateinit var ds: DataSource

    @Autowired
    lateinit var redisFactory: RedisConnectionFactory

    lateinit var jdbc: JdbcTemplate

    // ----------------------------------------------------------------
    // State reset — FK-safe DELETE + Redis flush
    // ----------------------------------------------------------------

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)

        // Delete in FK-safe order — new Phase C tables first
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy_entry")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy")
        // tenant_webauthn_snapshot: PSK_APP_ADMIN has SELECT+INSERT only (V27) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.tenant_webauthn_snapshot")
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

        // Inject PLATFORM_OPERATOR into SecurityContext so TenantBoundary passes
        val operator = AdminUserDetails(
            ACTOR_ID, ACTOR_EMAIL, "{noop}unused",
            "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(operator, null, operator.authorities)

        // Flush Redis
        val conn = redisFactory.connection
        try {
            conn.serverCommands().flushAll()
        } finally {
            conn.close()
        }
    }

    // ----------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------

    @Test
    fun aaguidPolicyCeremony_anyAllowlistDenylist() {
        // ── 1. Tenant 생성 → mode=ANY 정책 자동 생성 ──────────────────
        val createReq = TenantAdminDto.TenantCreateRequest(
            "policy-it", "Policy IT Tenant", "localhost", "Policy IT",
            listOf("http://localhost:9090"),
            setOf("none"),
            true, false,
            "NONE", 60000,
        )
        val tenant = tenantAdminService.create(createReq, ACTOR_ID, ACTOR_EMAIL)
        val tenantId = checkNotNull(tenant.id)

        // policy row 자동 생성 검증
        val policy = policyRepo.findById(tenantId)
            .orElseThrow { AssertionError("aaguid policy row not found after tenant create") }
        assertThat(policy.mode)
            .`as`("초기 mode 는 ANY 여야 한다")
            .isEqualTo(TenantAaguidPolicy.Mode.ANY)
        assertThat(policy.isMdsStrict)
            .`as`("초기 mdsStrict 는 false 여야 한다")
            .isFalse()

        // ── 2. ALLOWLIST 설정: entries=[aaguidA] ──────────────────────
        val allowlistReq = AaguidPolicyDto.UpdateRequest(
            TenantAaguidPolicy.Mode.ALLOWLIST,
            false,
            listOf(AaguidPolicyDto.EntryInput(AAGUID_A, "test-aaguid-a")),
        )
        aaguidPolicyService.update(tenantId, allowlistReq, ACTOR_EMAIL)

        // ── 3. check(aaguidA) → 통과 (예외 없음) ─────────────────────
        assertThatCode { policyChecker.check(tenantId, AAGUID_A) }
            .`as`("ALLOWLIST: aaguidA 는 허용 목록에 있으므로 예외 없어야 한다")
            .doesNotThrowAnyException()

        // ── 4. check(aaguidB) → AaguidPolicyViolationException ───────
        assertThatThrownBy { policyChecker.check(tenantId, AAGUID_B) }
            .`as`("ALLOWLIST: aaguidB 는 허용 목록에 없으므로 예외 발생해야 한다")
            .isInstanceOf(AaguidPolicyViolationException::class.java)

        // ── 5. DENYLIST 설정: entries=[aaguidA] ───────────────────────
        val denylistReq = AaguidPolicyDto.UpdateRequest(
            TenantAaguidPolicy.Mode.DENYLIST,
            false,
            listOf(AaguidPolicyDto.EntryInput(AAGUID_A, "deny-aaguid-a")),
        )
        aaguidPolicyService.update(tenantId, denylistReq, ACTOR_EMAIL)

        // ── 6. check(aaguidA) → 예외 (DENIED) ────────────────────────
        assertThatThrownBy { policyChecker.check(tenantId, AAGUID_A) }
            .`as`("DENYLIST: aaguidA 는 차단 목록에 있으므로 예외 발생해야 한다")
            .isInstanceOf(AaguidPolicyViolationException::class.java)

        // ── 7. check(aaguidB) → 통과 ─────────────────────────────────
        assertThatCode { policyChecker.check(tenantId, AAGUID_B) }
            .`as`("DENYLIST: aaguidB 는 차단 목록에 없으므로 예외 없어야 한다")
            .doesNotThrowAnyException()
    }
}
