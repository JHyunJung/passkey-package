package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.AdminApplication
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource

/**
 * F01 — MFA 필수 정책 강제화 통합 테스트.
 *
 * 정책 on 에서 MFA 미등록 운영자(alice)는 콘솔 API 가 막히고, 로그인 화면의 등록 흐름
 * (enroll → TOTP confirm)을 마쳐야 쓸 수 있다. 테스트 DB 는 testfix V9002 로 정책이 'N' 이라
 * 이 IT 가 owner JDBC 로 'Y' 로 올렸다가 끝나면 'N'·alice 의 MFA 컬럼을 원복한다.
 */
// classes 명시: auth 패키지의 MfaController*Test$SliceConfig 자동탐색 충돌 회피(MultiTenantOperatorIT 참고).
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AdminApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Testcontainers
class MfaRequiredPolicyIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (identical to AdminFlowIT / TenantAdminControllerUpdateIT)
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
                poolName = "mfa-required-policy-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
        }

        private fun mergeSetCookiesByName(jar: MutableMap<String, String>, setCookies: List<String>?) {
            if (setCookies == null) return
            for (sc in setCookies) {
                val pair = sc.split(";", limit = 2)[0]
                val eq = pair.indexOf('=')
                if (eq < 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1)
                if (name.isEmpty()) continue
                if (value.isEmpty()) {
                    jar.remove(name)
                } else {
                    jar[name] = value
                }
            }
        }

        private fun renderCookieHeader(jar: Map<String, String>): String {
            val sb = StringBuilder()
            for ((k, v) in jar) {
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(k).append('=').append(v)
            }
            return sb.toString()
        }
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var om: ObjectMapper

    @Autowired
    lateinit var ds: DataSource

    @Autowired
    lateinit var redisFactory: RedisConnectionFactory

    lateinit var jdbc: JdbcTemplate

    // ----------------------------------------------------------------
    // Wiring / state
    // ----------------------------------------------------------------

    private val totp = TotpService()

    private fun setPolicy(on: Boolean) {
        ownerJdbc().update("UPDATE PSK_APP_OWNER.security_policy SET mfa_required = ? WHERE id = 1", if (on) "Y" else "N")
    }

    /** 다른 IT 와 공유하는 컨테이너 — alice 의 MFA 컬럼·복구코드·정책을 시드 상태(N)로 되돌린다. */
    private fun restoreState() {
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.admin_user_recovery_code")
        ownerJdbc().update(
            "UPDATE PSK_APP_OWNER.admin_user SET mfa_enabled = 'N', mfa_secret = NULL, mfa_last_totp_step = NULL " +
                "WHERE email = 'alice@crosscert.com'",
        )
        setPolicy(false)
    }

    @BeforeEach
    fun setUp() {
        restoreState()
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    @AfterEach
    fun tearDown() = restoreState()

    private fun url(path: String): String = "http://localhost:$port$path"

    private class Session(val jar: MutableMap<String, String>, val loginBody: JsonNode)

    private fun headers(s: Session, json: Boolean = true, htmx: Boolean = false): HttpHeaders {
        val h = HttpHeaders()
        h.contentType = if (json) MediaType.APPLICATION_JSON else MediaType.APPLICATION_FORM_URLENCODED
        h.set(HttpHeaders.COOKIE, renderCookieHeader(s.jar))
        s.jar["XSRF-TOKEN"]?.let { h.set("X-XSRF-TOKEN", it) }
        if (htmx) h.set("HX-Request", "true")
        return h
    }

    private fun call(s: Session, method: HttpMethod, path: String, body: String? = null, htmx: Boolean = false, json: Boolean = true) =
        rest.exchange(url(path), method, HttpEntity(body, headers(s, json, htmx)), String::class.java).also {
            mergeSetCookiesByName(s.jar, it.headers[HttpHeaders.SET_COOKIE])
        }

    private fun login(email: String, password: String): Session {
        val jar = LinkedHashMap<String, String>()
        val seed = rest.exchange(url("/admin/api/me"), HttpMethod.GET, HttpEntity<Any>(HttpHeaders()), String::class.java)
        mergeSetCookiesByName(jar, seed.headers[HttpHeaders.SET_COOKIE])
        assertThat(jar["XSRF-TOKEN"]).isNotNull()
        val h = HttpHeaders()
        h.contentType = MediaType.APPLICATION_FORM_URLENCODED
        h.set(HttpHeaders.COOKIE, renderCookieHeader(jar))
        h.set("X-XSRF-TOKEN", jar["XSRF-TOKEN"])
        val res = rest.exchange(
            url("/admin/login"), HttpMethod.POST,
            HttpEntity("email=$email&password=$password", h), String::class.java,
        )
        assertThat(res.statusCode.is2xxSuccessful).`as`("login: %s %s", res.statusCode, res.body).isTrue()
        mergeSetCookiesByName(jar, res.headers[HttpHeaders.SET_COOKIE])
        val s = Session(jar, om.readTree(res.body))
        // 인증 후 새 CSRF 토큰 확보(/me 는 등록 필요 세션에도 열려 있다).
        call(s, HttpMethod.GET, "/admin/api/me")
        return s
    }

    private fun enrollAndConfirm(s: Session): String {
        val enroll = call(s, HttpMethod.POST, "/admin/login/mfa/enroll", htmx = true, json = false)
        assertThat(enroll.statusCode.is2xxSuccessful).`as`("enroll: %s", enroll.statusCode).isTrue()
        val secret = Regex("class=\"secret__value\"[^>]*>([A-Z2-7]+)<").find(checkNotNull(enroll.body))
            ?.groupValues?.get(1)
        assertThat(secret).`as`("enroll fragment must contain Base32 secret").isNotNull()
        val code = totp.generate(secret, System.currentTimeMillis())
        val confirm = call(s, HttpMethod.POST, "/admin/login/mfa/confirm", "code=$code", htmx = true, json = false)
        assertThat(confirm.statusCode.is2xxSuccessful).`as`("confirm: %s %s", confirm.statusCode, confirm.body).isTrue()
        return secret!!
    }

    private fun tenants(s: Session) = call(s, HttpMethod.GET, "/admin/api/tenants")

    // ----------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------

    @Test
    fun policyOn_unenrolledOperator_mustEnrollBeforeUsingConsole() {
        setPolicy(true)
        val s = login("alice@crosscert.com", "alice-temp-pw")
        assertThat(s.loginBody.get("mfaEnrollRequired").asBoolean()).isTrue()

        val blocked = tenants(s)
        assertThat(blocked.statusCode.value()).isEqualTo(403)
        assertThat(om.readTree(blocked.body).get("error").asString()).isEqualTo("mfa_enroll_required")

        enrollAndConfirm(s)

        assertThat(tenants(s).statusCode.value()).isEqualTo(200)
        val rows = ownerJdbc().queryForList(
            "SELECT payload FROM PSK_APP_OWNER.audit_log WHERE action = 'MFA_ENROLL'",
            String::class.java,
        )
        assertThat(rows).hasSize(1)
        assertThat(om.readTree(rows[0]).get("forced").asBoolean()).isTrue()
    }

    @Test
    fun policyOn_abandonedEnrollment_requiredAgainOnNextLogin() {
        setPolicy(true)
        val s = login("alice@crosscert.com", "alice-temp-pw")
        val enroll = call(s, HttpMethod.POST, "/admin/login/mfa/enroll", htmx = true, json = false)
        assertThat(enroll.statusCode.is2xxSuccessful).isTrue()
        val out = call(s, HttpMethod.POST, "/admin/logout")
        assertThat(out.statusCode.is2xxSuccessful || out.statusCode.is3xxRedirection).isTrue()

        val again = login("alice@crosscert.com", "alice-temp-pw")
        assertThat(again.loginBody.get("mfaEnrollRequired").asBoolean()).isTrue()
        assertThat(tenants(again).statusCode.value()).isEqualTo(403)
    }

    @Test
    fun policyOn_staleEnrollRequiredSession_cannotReplaceSecretAfterEnrollment() {
        setPolicy(true)
        val a = login("alice@crosscert.com", "alice-temp-pw") // 오래된 등록 필요 세션(예: 탈취된 비밀번호)
        val b = login("alice@crosscert.com", "alice-temp-pw")
        enrollAndConfirm(b)
        fun sealedSecret(): String? = ownerJdbc().queryForObject(
            "SELECT mfa_secret FROM PSK_APP_OWNER.admin_user WHERE email = 'alice@crosscert.com'",
            String::class.java,
        )
        val before = sealedSecret()
        assertThat(before).isNotNull()

        val enroll = call(a, HttpMethod.POST, "/admin/login/mfa/enroll", htmx = true, json = false)
        assertThat(enroll.headers.getFirst("HX-Redirect")).isEqualTo("/admin/login")
        assertThat(enroll.body.orEmpty()).doesNotContain("secret__value")
        // 세션 A 는 이제 TOTP 확인 대기 — 콘솔 API·MFA 변경 API 모두 막힌다.
        assertThat(call(a, HttpMethod.POST, "/admin/api/mfa/enroll").statusCode.value()).isEqualTo(403)
        assertThat(tenants(a).statusCode.value()).isEqualTo(403)

        assertThat(sealedSecret()).isEqualTo(before)
        val enabled = ownerJdbc().queryForObject(
            "SELECT mfa_enabled FROM PSK_APP_OWNER.admin_user WHERE email = 'alice@crosscert.com'",
            String::class.java,
        )
        assertThat(enabled).isEqualTo("Y")
    }

    @Test
    fun policyOn_disableRejected() {
        setPolicy(true)
        val s = login("alice@crosscert.com", "alice-temp-pw")
        enrollAndConfirm(s)

        val res = call(s, HttpMethod.POST, "/admin/api/mfa/disable", """{"code":"000000"}""")
        assertThat(res.statusCode.value()).isEqualTo(409)
        assertThat(om.readTree(res.body).get("error").asString()).isEqualTo("mfa_policy_required")
    }

    @Test
    fun policyOff_unenrolledOperator_unchanged() {
        val s = login("alice@crosscert.com", "alice-temp-pw")
        assertThat(s.loginBody.get("mfaEnrollRequired").asBoolean()).isFalse()
        assertThat(tenants(s).statusCode.value()).isEqualTo(200)
    }
}
