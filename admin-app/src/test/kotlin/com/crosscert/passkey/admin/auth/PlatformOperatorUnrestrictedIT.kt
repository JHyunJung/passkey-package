package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.AdminApplication
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
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
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource

/**
 * T14 — PlatformOperatorUnrestrictedIT: PLATFORM_OPERATOR 무제한 동작 검증 (자동 IT 2).
 *
 * alice (PLATFORM_OPERATOR) 로 10 assertion 실행. role 변경이 기존 운영 능력을
 * 안 깨뜨림을 보장하는 회귀 채널.
 *
 * Testcontainers + loginAs inline 패턴은 T13 RpAdminBoundaryIT 에서 복사.
 */
// classes 명시: auth 패키지의 MfaController*Test$SliceConfig 자동탐색 충돌 회피(MultiTenantOperatorIT 참고).
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AdminApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Testcontainers
class PlatformOperatorUnrestrictedIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (identical to RpAdminBoundaryIT / AdminFlowIT)
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
                poolName = "platform-operator-it-owner"
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

    /** Plain RestTemplate so 4xx throws HttpClientErrorException (not swallowed). */
    private val http = RestTemplate()

    lateinit var jdbc: JdbcTemplate

    // ----------------------------------------------------------------
    // State reset — alice remains PLATFORM_OPERATOR (no tenant), clean slate
    // ----------------------------------------------------------------

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)
        // audit_log: PSK_APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key")
        jdbc.update("DELETE FROM PSK_APP_OWNER.credential")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format")
        // Clear admin_user_tenant mapping before deleting tenants (FK admin_user_tenant.tenant_id → tenant)
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant")
        jdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant")
        // Re-seed demo-rp tenant (seeded by V23; deleted above for clean slate).
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                require_user_verification, mds_required, created_at, updated_at)
            VALUES (HEXTORAW('0000000000000000000000000000C0DE'),
                'demo-rp', 'Demo RP', 'localhost', 'Demo RP', 'active', 'Y', 'N',
                SYSTIMESTAMP, SYSTIMESTAMP)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
            VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'), 'http://localhost:9090', 0)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format)
            VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'), 'none')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format)
            VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'), 'packed')
            """.trimIndent(),
        )
        // Ensure alice is PLATFORM_OPERATOR with no tenant. (MFA is disabled
        // for the seed users by the test-only migration V9000 so logins here
        // are not gated by MfaPendingFilter; see db/testfix.)
        // alice 는 PLATFORM_OPERATOR — 매핑 0개(전체 접근). tenant_id 컬럼 제거됨(N:M 전환).
        jdbc.update(
            """
            UPDATE PSK_APP_OWNER.admin_user
               SET role = 'PLATFORM_OPERATOR'
             WHERE email = 'alice@crosscert.com'
            """.trimIndent(),
        )
        // Flush Redis to clear scheduler leases and session state
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    // ----------------------------------------------------------------
    // Login helper (inlined from RpAdminBoundaryIT.loginAs)
    // ----------------------------------------------------------------

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun loginAs(email: String, password: String): HttpHeaders {
        val jar = LinkedHashMap<String, String>()

        // 1. Seed CSRF cookie via unauthenticated GET.
        val seed = rest.exchange(
            url("/admin/api/me"), HttpMethod.GET,
            HttpEntity<Any>(HttpHeaders()), String::class.java,
        )
        mergeSetCookiesByName(jar, seed.headers[HttpHeaders.SET_COOKIE])
        assertThat(jar["XSRF-TOKEN"])
            .`as`("CSRF cookie should be emitted on seed request (even 401)")
            .isNotNull()

        // 2. POST form login.
        val loginHeaders = HttpHeaders()
        loginHeaders.contentType = MediaType.APPLICATION_FORM_URLENCODED
        loginHeaders.set(HttpHeaders.COOKIE, renderCookieHeader(jar))
        loginHeaders.set("X-XSRF-TOKEN", jar["XSRF-TOKEN"])
        val body = "email=$email&password=$password"
        val login = rest.exchange(
            url("/admin/login"), HttpMethod.POST,
            HttpEntity(body, loginHeaders), String::class.java,
        )
        assertThat(login.statusCode.is2xxSuccessful)
            .`as`("login as %s: %s body=%s", email, login.statusCode, login.body)
            .isTrue()
        mergeSetCookiesByName(jar, login.headers[HttpHeaders.SET_COOKIE])

        // 3. Warm-up GET /me — guarantees fresh CSRF token for authenticated session.
        val warmHeaders = HttpHeaders()
        warmHeaders.set(HttpHeaders.COOKIE, renderCookieHeader(jar))
        val warm = rest.exchange(
            url("/admin/api/me"), HttpMethod.GET,
            HttpEntity<Any>(warmHeaders), String::class.java,
        )
        mergeSetCookiesByName(jar, warm.headers[HttpHeaders.SET_COOKIE])

        val auth = HttpHeaders()
        auth.contentType = MediaType.APPLICATION_JSON
        auth.set(HttpHeaders.COOKIE, renderCookieHeader(jar))
        auth.set("X-XSRF-TOKEN", jar["XSRF-TOKEN"])
        return auth
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun createTenantViaApi(auth: HttpHeaders, slug: String, displayName: String): String {
        val body = mapOf(
            "slug" to slug,
            "displayName" to displayName,
            "rpId" to "localhost",
            "rpName" to "RP for $slug",
            "allowedOrigins" to listOf("http://localhost:9090"),
            "acceptedFormats" to listOf("none", "packed"),
            "requireUserVerification" to true,
            "mdsRequired" to false,
            "attestationConveyance" to "NONE",
            "webauthnTimeoutMs" to 60000,
        )
        val res = rest.exchange(
            url("/admin/api/tenants"),
            HttpMethod.POST,
            HttpEntity(om.writeValueAsString(body), auth),
            JsonNode::class.java,
        )
        assertThat(res.statusCode.is2xxSuccessful)
            .`as`("create tenant slug=%s: %s body=%s", slug, res.statusCode, res.body)
            .isTrue()
        return checkNotNull(res.body).get("data").get("id").asString()
    }

    // ----------------------------------------------------------------
    // Test — 10 assertions
    // ----------------------------------------------------------------

    @Test
    fun platformOperator_canAccessAllTenants_andRunPlatformOperations() {
        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")

        // 1. GET /me → PLATFORM_OPERATOR, tenantId=null
        val me = http.exchange(
            url("/admin/api/me"), HttpMethod.GET, HttpEntity<Any>(auth), JsonNode::class.java,
        )
        val meBody = checkNotNull(me.body)
        assertThat(meBody.get("data").get("role").asString()).isEqualTo("PLATFORM_OPERATOR")
        // PLATFORM_OPERATOR 는 매핑 0개 → tenantIds 빈 배열(전체 접근 의미). (N:M: 단수 tenantId → 배열)
        val tenantIdsNode = meBody.get("data").path("tenantIds")
        assertThat(
            tenantIdsNode.isMissingNode ||
                (tenantIdsNode.isArray && tenantIdsNode.isEmpty),
        )
            .`as`("PLATFORM_OPERATOR tenantIds must be empty/absent: %s", tenantIdsNode).isTrue()

        // 2. tenant_A 생성
        val tenantAId = createTenantViaApi(auth, "platform-it-a", "Tenant A")

        // 3. GET /tenants — demo-rp + platform-it-a 모두
        val tList = http.exchange(
            url("/admin/api/tenants"), HttpMethod.GET, HttpEntity<Any>(auth), JsonNode::class.java,
        )
        val tListData = checkNotNull(tList.body).get("data")
        val slugs = tListData.values().map { n -> n.get("slug").asString() }
        assertThat(slugs).contains("demo-rp", "platform-it-a")

        // 4. PUT /tenants/{tenant_A} → 200
        val updateA = mapOf(
            "displayName" to "Updated A",
            "rpId" to "localhost",
            "rpName" to "RP for platform-it-a",
            "allowedOrigins" to listOf("http://localhost:9090"),
            "acceptedFormats" to listOf("none", "packed"),
            "requireUserVerification" to true,
            "mdsRequired" to false,
            "attestationConveyance" to "NONE",
            "webauthnTimeoutMs" to 60000,
        )
        val putA = http.exchange(
            url("/admin/api/tenants/$tenantAId"), HttpMethod.PUT,
            HttpEntity(om.writeValueAsString(updateA), auth),
            JsonNode::class.java,
        )
        assertThat(putA.statusCode.is2xxSuccessful).isTrue()

        // 5. PUT /tenants/{demo-rp} → 200 (RP 의 tenant 도 자유)
        val demoRpId = tListData.values()
            .first { n -> "demo-rp" == n.get("slug").asString() }
            .get("id").asString()
        val updateDemo = mapOf(
            "displayName" to "Demo RP (updated by alice)",
            "rpId" to "localhost",
            "rpName" to "Demo RP",
            "allowedOrigins" to listOf("http://localhost:9090"),
            "acceptedFormats" to listOf("none", "packed"),
            "requireUserVerification" to true,
            "mdsRequired" to false,
            "attestationConveyance" to "NONE",
            "webauthnTimeoutMs" to 60000,
        )
        val putDemo = http.exchange(
            url("/admin/api/tenants/$demoRpId"), HttpMethod.PUT,
            HttpEntity(om.writeValueAsString(updateDemo), auth),
            JsonNode::class.java,
        )
        assertThat(putDemo.statusCode.is2xxSuccessful).isTrue()

        // 6. GET /tenants/{demo-rp}/credentials → 200
        val credsDemo = http.exchange(
            url("/admin/api/tenants/$demoRpId/credentials"),
            HttpMethod.GET, HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(credsDemo.statusCode.is2xxSuccessful).isTrue()

        // 7. POST /api-keys {tenantId: demo-rp} → 200
        val keyForDemo = mapOf(
            "tenantId" to demoRpId,
            "name" to "alice-key-for-demo",
            "scopes" to listOf("registration"),
        )
        val keyDemo = http.exchange(
            url("/admin/api/api-keys"), HttpMethod.POST,
            HttpEntity(om.writeValueAsString(keyForDemo), auth),
            JsonNode::class.java,
        )
        assertThat(keyDemo.statusCode.is2xxSuccessful).isTrue()

        // 8. POST /keys/rotate → 200
        val rotate = http.exchange(
            url("/admin/api/keys/rotate"), HttpMethod.POST,
            HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(rotate.statusCode.is2xxSuccessful).isTrue()

        // 9. GET /mds/status → 200
        val mdsStatus = http.exchange(
            url("/admin/api/mds/status"), HttpMethod.GET,
            HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(mdsStatus.statusCode.is2xxSuccessful).isTrue()

        // 10. GET /audit → 200
        val audit = http.exchange(
            url("/admin/api/audit"), HttpMethod.GET,
            HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(audit.statusCode.is2xxSuccessful).isTrue()
    }
}
