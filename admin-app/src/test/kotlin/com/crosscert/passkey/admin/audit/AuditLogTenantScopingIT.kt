package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.admin.AdminApplication
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowingConsumer
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
import org.springframework.web.client.HttpClientErrorException
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
 * T8 — AuditLogTenantScopingIT: RP_ADMIN cross-tenant 차단 회귀 채널 (자동 IT 2/2).
 *
 * bob (RP_ADMIN, demo-rp) 가 GET /admin/api/audit 을 호출했을 때:
 *  - `tenantId=demoRp` (self) → 200, demo-rp row 만
 *  - `tenantId` 생략 → 200, service auto-scope = demo-rp
 *  - `tenantId=tenantA` (other) → 403 ACCESS_DENIED
 *
 * service boundary 또는 controller scope 분기 누락 시 즉시 회귀 탐지.
 *
 * Note (Jackson non_null + AuditLogRepository.search 의 WHERE 절):
 * RP_ADMIN 의 effectiveTenantId = scope.get() (demo-rp) 라서 query 는
 * `a.tenantId = :tenantId` 가 되고 `tenant_id IS NULL` row 는
 * 제외된다. 따라서 응답의 모든 row 는 `tenantId == demoRp` 여야 한다
 * (null 가능성 없음). Jackson `default-property-inclusion: non_null` 이
 * 적용되어도, tenantId 가 항상 non-null 이므로 missing-node 도 발생 안 한다.
 *
 * Testcontainers + loginAs inline 패턴은 RpAdminBoundaryIT 에서 복사.
 */
// classes 명시: auth 패키지의 MfaController*Test$SliceConfig 자동탐색 충돌 회피(단독실행 가능).
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AdminApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Testcontainers
class AuditLogTenantScopingIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (identical to ActivityControllerIT / RpAdminBoundaryIT)
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
                poolName = "audit-scoping-it-owner"
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
    // State reset — bob @ demo-rp RP_ADMIN, alice PLATFORM_OPERATOR.
    // Mirrors RpAdminBoundaryIT's FK-safe pattern (V23 fk_admin_user_tenant).
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
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant")
        jdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant")
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
        // bob → RP_ADMIN @ demo-rp (role + admin_user_tenant 매핑; N:M 전환).
        jdbc.update(
            """
            UPDATE PSK_APP_OWNER.admin_user
               SET role = 'RP_ADMIN'
             WHERE email = 'bob@crosscert.com'
            """.trimIndent(),
        )
        jdbc.update(
            """
            MERGE INTO PSK_APP_OWNER.admin_user_tenant t
            USING (SELECT HEXTORAW('00000000000000000000000000000011') AS aid,
                          HEXTORAW('0000000000000000000000000000C0DE') AS tid FROM dual) s
               ON (t.admin_user_id = s.aid AND t.tenant_id = s.tid)
             WHEN NOT MATCHED THEN
               INSERT (admin_user_id, tenant_id, created_at, created_by)
               VALUES (s.aid, s.tid, SYSTIMESTAMP, 'it')
            """.trimIndent(),
        )
        // alice → PLATFORM_OPERATOR (매핑 0개 = 전체 접근)
        jdbc.update(
            """
            UPDATE PSK_APP_OWNER.admin_user
               SET role = 'PLATFORM_OPERATOR'
             WHERE email = 'alice@crosscert.com'
            """.trimIndent(),
        )
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    // ----------------------------------------------------------------
    // Login helpers (inlined from RpAdminBoundaryIT.loginAs)
    // ----------------------------------------------------------------

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun loginAs(email: String, password: String): HttpHeaders {
        val jar = LinkedHashMap<String, String>()

        val seed = rest.exchange(
            url("/admin/api/me"), HttpMethod.GET,
            HttpEntity<Any>(HttpHeaders()), String::class.java,
        )
        mergeSetCookiesByName(jar, seed.headers[HttpHeaders.SET_COOKIE])
        assertThat(jar["XSRF-TOKEN"])
            .`as`("CSRF cookie should be emitted on seed request (even 401)")
            .isNotNull()

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

    private fun lookupTenantIdBySlug(auth: HttpHeaders, slug: String): String {
        val res = rest.exchange(
            url("/admin/api/tenants"), HttpMethod.GET,
            HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(res.statusCode.is2xxSuccessful)
            .`as`("GET /admin/api/tenants: %s body=%s", res.statusCode, res.body)
            .isTrue()
        return checkNotNull(res.body).get("data").values()
            .filter { n -> slug == n.get("slug").asString() }
            .map { n -> n.get("id").asString() }
            .firstOrNull()
            ?: throw AssertionError("tenant slug=$slug not found")
    }

    /** Asserts the call throws HttpClientErrorException with HTTP 403. */
    private fun assertForbidden(call: () -> Unit) {
        assertThatThrownBy { call() }
            .isInstanceOf(HttpClientErrorException::class.java)
            .satisfies(
                ThrowingConsumer { e ->
                    assertThat((e as HttpClientErrorException).statusCode.value())
                        .isEqualTo(403)
                },
            )
    }

    // ----------------------------------------------------------------
    // Test — RP_ADMIN cross-tenant audit boundary (5 assertions)
    // ----------------------------------------------------------------

    @Test
    fun rpAdminCanReadOwnTenantAuditOnly() {
        // ─── 1. alice (PLATFORM_OPERATOR) seeds tenant_A ──────────────────
        // TENANT_CREATE audit row 의 tenant_id = tenant_A.id 가 기록된다.
        val aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw")
        val tenantA = createTenantViaApi(aliceAuth, "tenant-a-scoping", "Tenant A Scoping")
        val demoRp = lookupTenantIdBySlug(aliceAuth, "demo-rp")

        // tenant_id = demoRp 가 달린 audit row 를 만들기 위해 demo-rp 도 PUT
        // (TENANT_UPDATE → tenant_id = demoRp). 그렇지 않으면 demo-rp 관련
        // audit row 가 없어 assertion 이 vacuous 해진다.
        val updateBody = mapOf(
            "displayName" to "Demo RP Updated",
            "rpId" to "localhost",
            "rpName" to "Demo RP",
            "allowedOrigins" to listOf("http://localhost:9090"),
            "acceptedFormats" to listOf("none", "packed"),
            "requireUserVerification" to true,
            "mdsRequired" to false,
            "attestationConveyance" to "NONE",
            "webauthnTimeoutMs" to 60000,
        )
        val upd = rest.exchange(
            url("/admin/api/tenants/$demoRp"), HttpMethod.PUT,
            HttpEntity(om.writeValueAsString(updateBody), aliceAuth),
            JsonNode::class.java,
        )
        assertThat(upd.statusCode.is2xxSuccessful)
            .`as`("seed demo-rp TENANT_UPDATE: %s body=%s", upd.statusCode, upd.body)
            .isTrue()

        // ─── 2. bob (RP_ADMIN, demo-rp) 로그인 ────────────────────────────
        val bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw")

        // ── Assertion 1 + 2: GET /audit?tenantId=demoRp → 200 + only demo-rp rows
        val r3 = http.exchange(
            url("/admin/api/audit?tenantId=$demoRp"), HttpMethod.GET,
            HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(r3.statusCode.is2xxSuccessful)
            .`as`("GET /audit?tenantId=demoRp: %s body=%s", r3.statusCode, r3.body)
            .isTrue()
        val rows3 = checkNotNull(r3.body).get("data")
        // 의미 있는 회귀 채널이 되려면 최소 1 row 가 있어야 한다 (vacuous truth 방지).
        assertThat(rows3.size())
            .`as`("at least one demo-rp audit row should exist after TENANT_UPDATE seed: %s", rows3)
            .isGreaterThanOrEqualTo(1)
        for (row in rows3) {
            // RP_ADMIN effectiveTenantId = demoRp → WHERE tenant_id = :demoRp
            // (tenant_id IS NULL row 는 제외). 모든 row 의 tenantId 는 정확히 demoRp.
            val tidNode = row.path("tenantId")
            assertThat(tidNode.isMissingNode || tidNode.isNull)
                .`as`("tenantId must be present (RP_ADMIN scope query excludes NULL): %s", row)
                .isFalse()
            assertThat(tidNode.asString())
                .`as`("every row must have tenantId == demoRp (no cross-tenant leak): %s", row)
                .isEqualTo(demoRp)
        }

        // ── Assertion 3 + 4: GET /audit (생략) → 200 + auto-scope to demo-rp
        val r4 = http.exchange(
            url("/admin/api/audit"), HttpMethod.GET,
            HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(r4.statusCode.is2xxSuccessful)
            .`as`("GET /audit (no param): %s body=%s", r4.statusCode, r4.body)
            .isTrue()
        val rows4 = checkNotNull(r4.body).get("data")
        assertThat(rows4.size())
            .`as`("at least one demo-rp audit row should appear under auto-scope: %s", rows4)
            .isGreaterThanOrEqualTo(1)
        for (row in rows4) {
            val tidNode = row.path("tenantId")
            assertThat(tidNode.isMissingNode || tidNode.isNull)
                .`as`("auto-scope must enforce tenant_id = demoRp (NULL excluded): %s", row)
                .isFalse()
            assertThat(tidNode.asString())
                .`as`("auto-scope: every row must be demoRp (no tenant_A leak): %s", row)
                .isEqualTo(demoRp)
        }

        // ── Assertion 5: GET /audit?tenantId=tenantA → 403 ACCESS_DENIED
        assertForbidden {
            http.exchange(
                url("/admin/api/audit?tenantId=$tenantA"), HttpMethod.GET,
                HttpEntity<Any>(bobAuth), JsonNode::class.java,
            )
        }
    }
}
