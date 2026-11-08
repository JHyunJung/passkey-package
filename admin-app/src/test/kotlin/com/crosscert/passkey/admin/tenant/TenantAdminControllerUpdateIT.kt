package com.crosscert.passkey.admin.tenant

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
import org.springframework.http.ResponseEntity
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
import java.util.stream.StreamSupport
import javax.sql.DataSource

/**
 * PUT /admin/api/tenants/{id} happy path + audit log row 생성 검증.
 *
 * AdminFlowIT 의 loginAs 가 private 이므로 Testcontainers 셋업과
 * 로그인 helper 를 inline 으로 복사한다.
 *
 * 시나리오:
 *   1. tenant 생성 (POST)
 *   2. PUT 으로 displayName + allowedOrigins 변경
 *   3. GET 으로 변경 확인
 *   4. GET /audit?action=TENANT_UPDATE → row + payload.changedFields 검증
 *   5. PUT 동일 body 재호출 → audit row 추가 안 됨 (no-op)
 */
// classes 명시: auth 패키지의 MfaController*Test$SliceConfig 자동탐색 충돌 회피(단독실행 가능).
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AdminApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Testcontainers
class TenantAdminControllerUpdateIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (same config as AdminFlowIT)
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
                poolName = "tenant-update-it-owner"
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
            for ((key, value) in jar) {
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(key).append('=').append(value)
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

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)
        // FK chain: api_key.tenant_id → tenant.id; credential.tenant_id →
        // tenant.id. audit_log has no FK. admin_user (V11 seed) is left
        // untouched so alice/bob stay logged-in-able across test runs.
        // V23 added admin_user.tenant_id FK → tenant.id: null it out before
        // deleting tenants so the FK constraint is not violated.
        // Child tables (api_key_scope, tenant_allowed_origin, tenant_accepted_format)
        // use ON DELETE CASCADE from their parent FKs, so deleting the
        // parent rows implicitly removes children too.
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
        // bob (RP_ADMIN) needs this tenant to exist and be assigned to him.
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
        // Re-assign bob to demo-rp as RP_ADMIN (role + admin_user_tenant 매핑; N:M 전환).
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
        val redisConn = redisFactory.connection
        try {
            redisConn.serverCommands().flushAll()
        } finally {
            redisConn.close()
        }
    }

    // ----------------------------------------------------------------
    // Login helper (inlined from AdminFlowIT.loginAs)
    // ----------------------------------------------------------------

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun loginAs(email: String, password: String): HttpHeaders {
        val jar: MutableMap<String, String> = LinkedHashMap()

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

    /** Unwrap ApiResponse envelope — fail fast on success=false. */
    private fun unwrap(res: ResponseEntity<JsonNode>): JsonNode {
        val env = res.body
            ?: throw AssertionError("API call returned empty body (status=" + res.statusCode + ")")
        if (!env.path("success").asBoolean()) {
            throw AssertionError(
                "API call failed (status=" + res.statusCode +
                    ", code=" + env.path("code").asString() +
                    ", message=" + env.path("message").asString() + ")",
            )
        }
        return env.path("data")
    }

    // ----------------------------------------------------------------
    // Test
    // ----------------------------------------------------------------

    @Test
    fun putUpdatesFields_andAppendsAuditRow_withChangedFields() {
        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")

        // 1. tenant 생성
        val createBody =
            """
            {"slug":"update-it","displayName":"Original Name","rpId":"localhost","rpName":"Original RP",
             "allowedOrigins":["http://localhost:9090"],
             "acceptedFormats":["none","packed"],
             "requireUserVerification":true,
             "mdsRequired":false,
             "attestationConveyance":"NONE",
             "webauthnTimeoutMs":60000}
            """.trimIndent() + "\n"
        val createRes = rest.exchange(
            url("/admin/api/tenants"),
            HttpMethod.POST,
            HttpEntity(createBody, auth),
            JsonNode::class.java,
        )
        assertThat(createRes.statusCode.is2xxSuccessful)
            .`as`("create tenant: %s body=%s", createRes.statusCode, createRes.body)
            .isTrue()
        val tenantId = unwrap(createRes).get("id").asString()

        // 2. PUT — displayName + allowedOrigins 변경
        val updateBody =
            """
            {"displayName":"Updated Name","rpId":"localhost","rpName":"Original RP",
             "allowedOrigins":["http://localhost:9090","http://localhost:9091"],
             "acceptedFormats":["none","packed"],
             "requireUserVerification":true,
             "mdsRequired":false,
             "attestationConveyance":"NONE",
             "webauthnTimeoutMs":60000}
            """.trimIndent() + "\n"
        val putRes = rest.exchange(
            url("/admin/api/tenants/$tenantId"),
            HttpMethod.PUT,
            HttpEntity(updateBody, auth),
            JsonNode::class.java,
        )
        assertThat(putRes.statusCode.is2xxSuccessful)
            .`as`("PUT tenant: %s body=%s", putRes.statusCode, putRes.body)
            .isTrue()
        assertThat(unwrap(putRes).get("displayName").asString()).isEqualTo("Updated Name")

        // 3. GET 확인
        val getRes = rest.exchange(
            url("/admin/api/tenants/$tenantId"),
            HttpMethod.GET,
            HttpEntity<Any>(auth),
            JsonNode::class.java,
        )
        val getData = unwrap(getRes)
        assertThat(getData.get("displayName").asString()).isEqualTo("Updated Name")
        assertThat(StreamSupport.stream(getData.get("allowedOrigins").spliterator(), false))
            .anyMatch { n -> n.asString() == "http://localhost:9091" }

        // 4. audit row 검증 — TENANT_UPDATE filter
        val auditRes = rest.exchange(
            url("/admin/api/audit?action=TENANT_UPDATE"),
            HttpMethod.GET,
            HttpEntity<Any>(auth),
            JsonNode::class.java,
        )
        val rows = unwrap(auditRes)
        assertThat(rows.isArray).isTrue()
        assertThat(rows.size())
            .`as`("TENANT_UPDATE audit rows")
            .isGreaterThanOrEqualTo(1)

        // 이 tenant 의 row 찾기
        val matchingRow = StreamSupport.stream(rows.spliterator(), false)
            .filter { r -> tenantId == r.get("targetId").asString() }
            .findFirst()
            .orElseThrow {
                AssertionError(
                    "TENANT_UPDATE row for tenant $tenantId not found in $rows",
                )
            }
        assertThat(matchingRow.get("action").asString()).isEqualTo("TENANT_UPDATE")

        // payload 는 String (AuditLogView.payload 가 String) — JSON 파싱
        val payloadNode = matchingRow.get("payload")
        val payload = if (payloadNode.isString) {
            om.readTree(payloadNode.asString())
        } else {
            payloadNode
        }

        val changedFields = payload.get("changedFields")
        assertThat(changedFields)
            .`as`("payload.changedFields must be present: %s", payload)
            .isNotNull()
        val changedList = StreamSupport.stream(changedFields.spliterator(), false)
            .map { it.asString() }
            .toList()
        assertThat(changedList).contains("displayName", "allowedOrigins")

        // 5. PUT 동일 body 재호출 → audit row 추가 안 됨 (no-op)
        val auditCountBefore = rows.size()
        val putAgain = rest.exchange(
            url("/admin/api/tenants/$tenantId"),
            HttpMethod.PUT,
            HttpEntity(updateBody, auth),
            JsonNode::class.java,
        )
        assertThat(putAgain.statusCode.is2xxSuccessful).isTrue()

        val auditAgain = rest.exchange(
            url("/admin/api/audit?action=TENANT_UPDATE"),
            HttpMethod.GET,
            HttpEntity<Any>(auth),
            JsonNode::class.java,
        )
        assertThat(unwrap(auditAgain).size())
            .`as`("no-op PUT must not append audit row")
            .isEqualTo(auditCountBefore)
    }
}
