package com.crosscert.passkey.admin.credential

import com.crosscert.passkey.admin.AdminApplication
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.repository.CredentialRepository
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
import org.springframework.http.ResponseEntity
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
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Cross-tenant boundary 회귀 채널. VPD 제거됨 — admin-app 은 cross-tenant 조회를
 * 하므로 service 의 entity.tenantId vs path.tenantId 비교가 단일 방어선.
 *
 * 시나리오:
 *   1. tenant_A, tenant_B 생성
 *   2. tenant_A 에 credential 1개 직접 fixture insert (CredentialRepository.save)
 *   3. GET /admin/api/tenants/{tenant_A}/credentials → credential 포함
 *   4. GET /admin/api/tenants/{tenant_B}/credentials → credential 미포함
 *   5. DELETE /admin/api/tenants/{tenant_B}/credentials/{credentialId} → 4xx ACCESS_DENIED (A002)
 *   6. credential row 여전히 DB 에 존재 (tenant_A 의 GET 에서 다시 확인)
 *
 * T6 의 TenantAdminControllerUpdateIT 와 동일한 Testcontainers + 로그인 inline 패턴 사용.
 */
// classes 명시: auth 패키지의 MfaController*Test$SliceConfig 자동탐색 충돌 회피(단독실행 가능).
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AdminApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Testcontainers
class CredentialAdminControllerSecurityIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (same config as AdminFlowIT and T6 TenantAdminControllerUpdateIT)
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
                poolName = "credential-security-it-owner"
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

    @Autowired
    lateinit var credentialRepository: CredentialRepository

    lateinit var jdbc: JdbcTemplate
    private val rng = SecureRandom()

    // Separate plain RestTemplate (not TestRestTemplate) for error response inspection
    private val http = RestTemplate()

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
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    // ----------------------------------------------------------------
    // Login helper (inlined from AdminFlowIT.loginAs / T6 TenantAdminControllerUpdateIT)
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
    // Helpers
    // ----------------------------------------------------------------

    private fun createTenantViaApi(auth: HttpHeaders, slug: String, displayName: String): String {
        val reqBody = mapOf(
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
            HttpEntity(om.writeValueAsString(reqBody), auth),
            JsonNode::class.java,
        )
        assertThat(res.statusCode.is2xxSuccessful)
            .`as`("create tenant slug=%s: %s body=%s", slug, res.statusCode, res.body)
            .isTrue()
        return unwrap(res).get("id").asString()
    }

    /**
     * Directly inserts a credential fixture into tenant_A via CredentialRepository.
     * Uses the Credential constructor (no individual setters exist).
     * Returns the credentialId as base64url (no padding) — the format used by admin endpoints.
     */
    private fun insertFixtureCredential(tenantId: UUID): String {
        val credId = ByteArray(16)
        rng.nextBytes(credId)
        val userHandle = ByteArray(32)
        rng.nextBytes(userHandle)
        val publicKey = byteArrayOf(0x01, 0x02, 0x03) // dummy; admin endpoint does not validate CBOR
        val aaguid = ByteArray(16) // zero aaguid

        val c = Credential(tenantId, userHandle, credId, publicKey, aaguid)
        credentialRepository.save(c)

        return Base64.getUrlEncoder().withoutPadding().encodeToString(credId)
    }

    // ----------------------------------------------------------------
    // Test
    // ----------------------------------------------------------------

    /**
     * Verifies cross-tenant boundary:
     * - tenant_A credential is visible in tenant_A list but NOT in tenant_B list
     * - DELETE via tenant_B path is rejected with 4xx + code=A002 (ACCESS_DENIED)
     * - credential still exists in tenant_A after failed cross-tenant revoke attempt
     */
    @Test
    fun crossTenantRevoke_isRejected_andCredentialIsListedOnlyInOwnTenant() {
        // Build auth headers using the same inline login pattern as T6
        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")

        // 1. Create two tenants
        val tenantAId = createTenantViaApi(auth, "sec-it-a-" + UUID.randomUUID().toString().substring(0, 8), "Tenant A")
        val tenantBId = createTenantViaApi(auth, "sec-it-b-" + UUID.randomUUID().toString().substring(0, 8), "Tenant B")

        // 2. Insert credential fixture directly into tenant_A (bypasses ceremony)
        val credentialIdB64 = insertFixtureCredential(UUID.fromString(tenantAId))

        // 3. GET tenant_A credentials → credential MUST be present
        val listA = rest.exchange(
            url("/admin/api/tenants/$tenantAId/credentials"),
            HttpMethod.GET,
            HttpEntity<Any>(auth),
            JsonNode::class.java,
        )
        assertThat(listA.statusCode.is2xxSuccessful)
            .`as`("GET tenant_A credentials: %s", listA.statusCode)
            .isTrue()
        val foundInA = unwrap(listA).get("content").values()
            .any { n -> credentialIdB64 == n.get("credentialId").asString() }
        assertThat(foundInA)
            .`as`("credential must appear in tenant_A list")
            .isTrue()

        // 4. GET tenant_B credentials → credential must NOT appear
        val listB = rest.exchange(
            url("/admin/api/tenants/$tenantBId/credentials"),
            HttpMethod.GET,
            HttpEntity<Any>(auth),
            JsonNode::class.java,
        )
        assertThat(listB.statusCode.is2xxSuccessful).isTrue()
        val foundInB = unwrap(listB).get("content").values()
            .any { n -> credentialIdB64 == n.get("credentialId").asString() }
        assertThat(foundInB)
            .`as`("tenant_A credential must NOT appear in tenant_B list")
            .isFalse()

        // 5. DELETE via tenant_B path → must be rejected with 4xx + code=A002
        // Use plain RestTemplate (not TestRestTemplate) so the exception carries the response body.
        // Transfer auth cookies & CSRF header from the TestRestTemplate session.
        val deleteHeaders = HttpHeaders()
        deleteHeaders.set(HttpHeaders.COOKIE, auth.getFirst(HttpHeaders.COOKIE))
        deleteHeaders.set("X-XSRF-TOKEN", auth.getFirst("X-XSRF-TOKEN"))
        deleteHeaders.contentType = MediaType.APPLICATION_JSON

        assertThatThrownBy {
            http.exchange(
                url("/admin/api/tenants/$tenantBId/credentials/$credentialIdB64"),
                HttpMethod.DELETE,
                HttpEntity<Any>(deleteHeaders),
                JsonNode::class.java,
            )
        }
            .isInstanceOf(HttpClientErrorException::class.java)
            .satisfies(
                ThrowingConsumer { e ->
                    val ex = e as HttpClientErrorException
                    assertThat(ex.statusCode.is4xxClientError)
                        .`as`("cross-tenant DELETE must be 4xx, got %s", ex.statusCode)
                        .isTrue()
                    val errorBody = om.readTree(ex.responseBodyAsString)
                    assertThat(errorBody.get("success").asBoolean())
                        .`as`("success must be false for access denied")
                        .isFalse()
                    assertThat(errorBody.get("code").asString())
                        .`as`("error code must be A002 (ACCESS_DENIED)")
                        .isEqualTo("A002")
                },
            )

        // 6. Credential still exists — tenant_A list still contains it after failed cross-tenant attempt
        val listAAfter = rest.exchange(
            url("/admin/api/tenants/$tenantAId/credentials"),
            HttpMethod.GET,
            HttpEntity<Any>(auth),
            JsonNode::class.java,
        )
        val stillInA = unwrap(listAAfter).get("content").values()
            .any { n -> credentialIdB64 == n.get("credentialId").asString() }
        assertThat(stillInA)
            .`as`("credential must still exist in tenant_A after rejected cross-tenant DELETE")
            .isTrue()
    }
}
