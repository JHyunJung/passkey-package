package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.AdminApplication
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.tenant.TenantAdminService
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * 가입 요청 → 목록 → 승인 → 로그인 가능 상태 풀 플로우 IT (Oracle Testcontainers + Redis).
 * V5 마이그레이션(테이블 생성·GRANT·초대 테이블 DROP)이 실제로 적용되는지도 여기서 확인된다.
 * 서비스 레이어 직접 호출 — 삭제된 AdminUserInvitationFlowIT 와 같은 패턴.
 */
// operator 패키지에 슬라이스용 중첩 @SpringBootConfiguration 이 있어 부트 클래스를 명시한다.
@SpringBootTest(classes = [AdminApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SignupRequestFlowIT {

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
        fun registerProps(reg: DynamicPropertyRegistry) {
            val exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba " +
                    "@/tmp/bootstrap-schema.sql",
            )
            if (exec.exitCode != 0) {
                throw IllegalStateException(
                    "bootstrap-schema.sql failed (exit=" + exec.exitCode + ")\n" +
                        "STDOUT:\n" + exec.stdout + "\nSTDERR:\n" + exec.stderr,
                )
            }
            reg.add("spring.datasource.url", ORACLE::getJdbcUrl)
            reg.add("spring.datasource.username") { "PSK_APP_ADMIN_USER" }
            reg.add("spring.datasource.password") { "admin_pw" }
            reg.add("spring.data.redis.host", REDIS::getHost)
            reg.add("spring.data.redis.port") { REDIS.getMappedPort(6379) }
        }

        private val ALICE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private const val ALICE_EMAIL = "alice@crosscert.com"
        private val SECURITY_CTX_ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

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
                poolName = "signup-flow-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
        }

        private val INT: Class<Int> = Int::class.javaObjectType
    }

    @Autowired
    lateinit var signupService: SignupRequestService

    @Autowired
    lateinit var signupRepo: AdminSignupRequestRepository

    @Autowired
    lateinit var adminUserRepo: AdminUserRepository

    @Autowired
    lateinit var tenantAdminService: TenantAdminService

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var ds: DataSource

    @Autowired
    lateinit var redisFactory: RedisConnectionFactory

    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_signup_request")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy_entry")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy")
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.tenant_webauthn_snapshot")
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key")
        jdbc.update("DELETE FROM PSK_APP_OWNER.credential")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format")
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant")
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.admin_user WHERE email NOT IN ('alice@crosscert.com','bob@crosscert.com')")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant")

        val operator = AdminUserDetails(
            SECURITY_CTX_ACTOR_ID, ALICE_EMAIL, "{noop}unused",
            "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(operator, null, operator.authorities)

        val conn = redisFactory.connection
        try {
            conn.serverCommands().flushAll()
        } finally {
            conn.close()
        }
    }

    @Test
    fun requestListApproveFlow_rpAdmin() {
        val createReq = TenantAdminDto.TenantCreateRequest(
            "signup-it", "Signup IT Tenant", "localhost", "Signup IT RP",
            listOf("http://localhost:9090"), setOf("none"), true, false, "NONE", 60000,
        )
        val tenantId = checkNotNull(tenantAdminService.create(createReq, ALICE_ID, ALICE_EMAIL).id)

        // 1. 공개 요청
        val password = "securePassword123!"
        signupService.request(SignupRequestDto.Create("NewAdmin@Example.com", password, "RP IAM 담당"))

        // 2. 목록 — 소문자 정규화된 이메일 1건
        val pending = signupService.list()
        assertThat(pending).hasSize(1)
        assertThat(pending[0].email).isEqualTo("newadmin@example.com")
        assertThat(pending[0].reason).isEqualTo("RP IAM 담당")
        val requestId = checkNotNull(pending[0].id)

        // 3. 같은 이메일 재요청은 조용히 무시
        signupService.request(SignupRequestDto.Create("newadmin@example.com", password, null))
        assertThat(signupRepo.count()).isEqualTo(1)

        // 4. 승인 — RP_ADMIN + 테넌트 1개
        val view = signupService.approve(
            requestId,
            SignupRequestDto.Approve("RP_ADMIN", listOf(tenantId)), ALICE_ID, ALICE_EMAIL,
        )
        assertThat(view.status).isEqualTo("ACTIVE")
        assertThat(view.role).isEqualTo("RP_ADMIN")
        assertThat(view.tenantIds).containsExactly(tenantId)

        // 5. 계정은 로그인 가능 상태이고 비밀번호는 요청 때 것
        val user = adminUserRepo.findByEmail("newadmin@example.com").orElseThrow()
        assertThat(user.isEnabled).isTrue()
        assertThat(user.status).isEqualTo("ACTIVE")
        assertThat(user.createdBy).isEqualTo(ALICE_EMAIL)
        assertThat(passwordEncoder.matches(password, user.bcryptHash)).isTrue()

        // 6. 요청은 소비됨 — 재승인은 404
        assertThat(signupService.list()).isEmpty()
        assertThatThrownBy {
            signupService.approve(
                requestId,
                SignupRequestDto.Approve("RP_ADMIN", listOf(tenantId)), ALICE_ID, ALICE_EMAIL,
            )
        }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }

        // 7. 승인 감사 행 존재
        val audits: Int? = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.audit_log WHERE action = 'ADMIN_SIGNUP_APPROVE'", INT,
        )
        assertThat(audits).isEqualTo(1)
    }

    @Test
    fun rejectFlow_removesRequestAndAudits() {
        signupService.request(SignupRequestDto.Create("reject@example.com", "securePassword123!", null))
        val requestId = checkNotNull(signupService.list()[0].id)

        signupService.reject(requestId, ALICE_ID, ALICE_EMAIL)

        assertThat(signupService.list()).isEmpty()
        assertThat(adminUserRepo.findByEmail("reject@example.com")).isEmpty()
        val audits: Int? = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.audit_log WHERE action = 'ADMIN_SIGNUP_REJECT'", INT,
        )
        assertThat(audits).isEqualTo(1)

        // 거절 후 같은 이메일로 재요청 가능
        signupService.request(SignupRequestDto.Create("reject@example.com", "securePassword123!", null))
        assertThat(signupService.list()).hasSize(1)
    }

    @Test
    fun existingAdminEmail_requestIsSilentlyIgnored() {
        signupService.request(SignupRequestDto.Create(ALICE_EMAIL, "securePassword123!", null))
        assertThat(signupService.list()).isEmpty()
    }
}
