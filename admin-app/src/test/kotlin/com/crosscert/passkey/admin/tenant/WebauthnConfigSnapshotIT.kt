package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
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
 * Phase C Task 6 — WebAuthn config 자동 snapshot 통합 테스트.
 *
 * 시나리오:
 *  1. Tenant 생성 → 초기 snapshot 1건 자동 생성 검증 (rpId = 생성 시 rpId)
 *  2. TenantAdminService.update() 호출 → snapshot 2건 (변경 직전 보존)
 *  3. 최신 snapshot 의 rpId == 생성 시 rpId (update 전 값 보존 확인)
 *
 * TenantAdminService 를 직접 호출하여 HTTP 레이어 없이 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WebauthnConfigSnapshotIT {

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
                poolName = "snapshot-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
        }
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @Autowired
    lateinit var tenantAdminService: TenantAdminService

    @Autowired
    lateinit var snapshotRepo: TenantWebauthnSnapshotRepository

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
    fun snapshotIsCreatedOnTenantCreate_andPreservedOnUpdate() {
        // ── 1. Tenant 생성 → 초기 snapshot 1건 자동 생성 ─────────────
        val initialRpId = "initial.example.com"
        val createReq = TenantAdminDto.TenantCreateRequest(
            "snap-it", "Snapshot IT Tenant", initialRpId, "Snapshot IT RP",
            listOf("http://localhost:9090"),
            setOf("none"),
            true, false,
            "NONE", 60000,
        )
        val tenant = tenantAdminService.create(createReq, ACTOR_ID, ACTOR_EMAIL)
        val tenantId = tenant.id

        val snapshotsAfterCreate = snapshotRepo.findByTenantIdOrderByTakenAtDesc(tenantId)
        assertThat(snapshotsAfterCreate)
            .`as`("tenant 생성 후 snapshot 1건이 자동 생성되어야 한다")
            .hasSize(1)
        assertThat(snapshotsAfterCreate[0].rpId)
            .`as`("초기 snapshot 의 rpId 는 생성 시 rpId 여야 한다")
            .isEqualTo(initialRpId)

        // ── 2. TenantAdminService.update() 호출 ───────────────────────
        // 주의: update() 는 rpId 변경을 silent ignore 하므로 (spec § 6.1),
        //       displayName 을 변경해서 실제 변경이 일어나도록 한다.
        // snapshot 은 "변경 직전" 값으로 INSERT 되므로 initialRpId 가 보존되어야 함.
        val updateReq = TenantAdminDto.TenantUpdateRequest(
            "Updated Snapshot IT Tenant", // displayName 변경
            initialRpId, // rpId (ignored by service, but must be non-null)
            "Snapshot IT RP Updated", // rpName 변경
            listOf("http://localhost:9090"),
            setOf("none"),
            true, false,
            "NONE", 60000,
        )
        tenantAdminService.update("snap-it", updateReq, ACTOR_ID, ACTOR_EMAIL)

        // ── 3. snapshot 2건 검증 ──────────────────────────────────────
        val snapshotsAfterUpdate = snapshotRepo.findByTenantIdOrderByTakenAtDesc(tenantId)
        assertThat(snapshotsAfterUpdate)
            .`as`("update 후 snapshot 은 총 2건이어야 한다 (create 1 + update 직전 1)")
            .hasSize(2)

        // ── 4. 가장 최근 snapshot (taken_at DESC 첫 번째) 의 rpId 검증 ──
        // update() 가 "변경 직전" 값으로 INSERT 하므로 최신 snapshot = update 직전 상태 = initialRpId
        val latestSnapshot = snapshotsAfterUpdate[0]
        assertThat(latestSnapshot.rpId)
            .`as`("update 후 가장 최근 snapshot 의 rpId 는 변경 전 값(initialRpId)을 보존해야 한다")
            .isEqualTo(initialRpId)
    }
}
