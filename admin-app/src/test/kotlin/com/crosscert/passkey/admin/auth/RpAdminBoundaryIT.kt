package com.crosscert.passkey.admin.auth

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
 * T13 — RpAdminBoundaryIT: cross-tenant 차단 회귀 채널 (자동 IT 1).
 *
 * bob (RP_ADMIN, demo-rp) 로 14 assertion 실행. service boundary 또는
 * `@PreAuthorize` 누락 시 200 으로 즉시 회귀 탐지.
 *
 * Testcontainers + loginAs inline 패턴은 AdminFlowIT 에서 복사.
 * resetState() 는 T12 AdminFlowIT 패턴 (bob 을 매 test 시작 시 RP_ADMIN(demo-rp)
 * 으로 reset) 그대로 사용.
 */
// classes 명시: auth 패키지의 MfaController*Test$SliceConfig 자동탐색 충돌 회피(MultiTenantOperatorIT 참고).
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AdminApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Testcontainers
class RpAdminBoundaryIT {

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
                poolName = "rp-admin-boundary-it-owner"
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
    // State reset — copies AdminFlowIT.resetState() (T12 FK pattern):
    //   bob is re-assigned to demo-rp as RP_ADMIN each test.
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
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy_entry")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy")
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
        // Re-seed demo-rp aaguid policy (ANY, mds_strict=N) — matches V26 backfill pattern.
        // resetState() inserts the tenant via raw SQL (not createTenantViaApi), so the
        // TenantAdminService auto-create is not triggered; we must seed it explicitly.
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_aaguid_policy
                (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
            VALUES (HEXTORAW('0000000000000000000000000000C0DE'),
                'ANY', 'N', SYSTIMESTAMP, SYSTIMESTAMP, 'test:reset')
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
    // Login helper (inlined from AdminFlowIT.loginAs)
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

    /**
     * Asserts that the given call throws [HttpClientErrorException] with HTTP 403.
     * Uses a plain RestTemplate (set as field `http`) so 4xx is not swallowed.
     */
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
    // Test — 14 assertions
    // ----------------------------------------------------------------

    @Test
    fun rpAdmin_seesAndMutatesOnlyOwnTenant_andCannotPlatformOperations() {
        // 사전: alice (PLATFORM_OPERATOR) 로 tenant_A 생성
        val aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw")
        val tenantAId = createTenantViaApi(aliceAuth, "boundary-it-a", "Tenant A")

        // bob (RP_ADMIN, demo-rp) 로그인
        val bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw")

        // ── 1. GET /me — role=RP_ADMIN + tenantIds (N:M: 단수 tenantId → 배열) ──
        val me = http.exchange(
            url("/admin/api/me"), HttpMethod.GET, HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(me.statusCode.is2xxSuccessful).isTrue()
        val meData = checkNotNull(me.body).get("data")
        assertThat(meData.get("role").asString())
            .`as`("bob must have role RP_ADMIN")
            .isEqualTo("RP_ADMIN")
        val tenantIds: JsonNode? = meData.get("tenantIds")
        assertThat(tenantIds != null && tenantIds.isArray && tenantIds.size() >= 1)
            .`as`("bob (RP_ADMIN) must have at least one mapped tenant: %s", tenantIds)
            .isTrue()
        // demo-rp 단일 매핑 — 활성/유일 RP id 로 사용.
        val myTenantId = checkNotNull(tenantIds).get(0).asString()
        assertThat(myTenantId).`as`("bob must have a tenantId").isNotBlank()

        // ── 2. GET /tenants — demo-rp 만 포함, boundary-it-a 미포함 ───────────
        val tList = http.exchange(
            url("/admin/api/tenants"), HttpMethod.GET, HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(tList.statusCode.is2xxSuccessful).isTrue()
        val slugs = checkNotNull(tList.body).get("data").values().map { n -> n.get("slug").asString() }
        assertThat(slugs)
            .`as`("bob sees only own tenant — actual slugs: %s", slugs)
            .contains("demo-rp")
            .doesNotContain("boundary-it-a")

        // ── 3. GET /tenants/{my} → 200 ────────────────────────────────────────
        val myT = http.exchange(
            url("/admin/api/tenants/$myTenantId"), HttpMethod.GET,
            HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(myT.statusCode.is2xxSuccessful)
            .`as`("bob GET own tenant must succeed")
            .isTrue()

        // ── 4. GET /tenants/{other} → 403 ────────────────────────────────────
        assertForbidden {
            http.exchange(
                url("/admin/api/tenants/$tenantAId"), HttpMethod.GET,
                HttpEntity<Any>(bobAuth), JsonNode::class.java,
            )
        }

        // ── 5. PUT /tenants/{my} → 200 ────────────────────────────────────────
        val updateBody = mapOf(
            "displayName" to "Updated by Bob",
            "rpId" to "localhost",
            "rpName" to "Demo RP",
            "allowedOrigins" to listOf("http://localhost:9090"),
            "acceptedFormats" to listOf("none", "packed"),
            "requireUserVerification" to true,
            "mdsRequired" to false,
            "attestationConveyance" to "NONE",
            "webauthnTimeoutMs" to 60000,
        )
        val putMy = http.exchange(
            url("/admin/api/tenants/$myTenantId"), HttpMethod.PUT,
            HttpEntity(om.writeValueAsString(updateBody), bobAuth),
            JsonNode::class.java,
        )
        assertThat(putMy.statusCode.is2xxSuccessful)
            .`as`("bob PUT own tenant must succeed")
            .isTrue()

        // ── 6. PUT /tenants/{other} → 403 ────────────────────────────────────
        val updateBodyJson = om.writeValueAsString(updateBody)
        assertForbidden {
            http.exchange(
                url("/admin/api/tenants/$tenantAId"), HttpMethod.PUT,
                HttpEntity(updateBodyJson, bobAuth),
                JsonNode::class.java,
            )
        }

        // ── 7. GET /tenants/{my}/credentials → 200 ───────────────────────────
        val credsMy = http.exchange(
            url("/admin/api/tenants/$myTenantId/credentials"),
            HttpMethod.GET, HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(credsMy.statusCode.is2xxSuccessful)
            .`as`("bob GET own tenant credentials must succeed")
            .isTrue()

        // ── 8. GET /tenants/{other}/credentials → 403 ────────────────────────
        assertForbidden {
            http.exchange(
                url("/admin/api/tenants/$tenantAId/credentials"),
                HttpMethod.GET, HttpEntity<Any>(bobAuth), JsonNode::class.java,
            )
        }

        // ── 9. POST /tenants → 403 (RP_ADMIN 은 tenant create 불가) ──────────
        val newTenant = mapOf(
            "slug" to "bob-attempt",
            "displayName" to "Bob Attempt",
            "rpId" to "localhost",
            "rpName" to "X",
            "allowedOrigins" to listOf("http://localhost:9090"),
            "acceptedFormats" to listOf("none"),
            "requireUserVerification" to true,
            "mdsRequired" to false,
            "attestationConveyance" to "NONE",
            "webauthnTimeoutMs" to 60000,
        )
        val newTenantJson = om.writeValueAsString(newTenant)
        assertForbidden {
            http.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                HttpEntity(newTenantJson, bobAuth),
                JsonNode::class.java,
            )
        }

        // ── 10. POST /api-keys (my tenant) → 200 ─────────────────────────────
        val myKey = mapOf(
            "tenantId" to myTenantId,
            "name" to "bob-key-own",
            "scopes" to listOf("registration", "authentication"),
        )
        val keyMy = http.exchange(
            url("/admin/api/api-keys"), HttpMethod.POST,
            HttpEntity(om.writeValueAsString(myKey), bobAuth),
            JsonNode::class.java,
        )
        assertThat(keyMy.statusCode.is2xxSuccessful)
            .`as`("bob POST api-key for own tenant must succeed")
            .isTrue()

        // ── 11. POST /api-keys (other tenant) → 403 ──────────────────────────
        val otherKey = mapOf(
            "tenantId" to tenantAId,
            "name" to "bob-key-other",
            "scopes" to listOf("registration"),
        )
        val otherKeyJson = om.writeValueAsString(otherKey)
        assertForbidden {
            http.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                HttpEntity(otherKeyJson, bobAuth),
                JsonNode::class.java,
            )
        }

        // ── 12. POST /keys/rotate → 403 ──────────────────────────────────────
        assertForbidden {
            http.exchange(
                url("/admin/api/keys/rotate"), HttpMethod.POST,
                HttpEntity<Any>(bobAuth), JsonNode::class.java,
            )
        }

        // ── 13. POST /mds/sync → 403 ─────────────────────────────────────────
        assertForbidden {
            http.exchange(
                url("/admin/api/mds/sync"), HttpMethod.POST,
                HttpEntity<Any>(bobAuth), JsonNode::class.java,
            )
        }

        // ── 14. GET /audit — semantics shift ─────────────────────────────────
        // admin-role-separation: GET /audit was PLATFORM_OPERATOR only → 403 for bob.
        // activity-page: hasAnyRole(PLATFORM_OPERATOR, RP_ADMIN) — bob now allowed,
        // but cross-tenant tenantId param → 403.
        //
        // (a) cross-tenant tenantId → 403 ACCESS_DENIED
        assertForbidden {
            http.exchange(
                url("/admin/api/audit?tenantId=$tenantAId"), HttpMethod.GET,
                HttpEntity<Any>(bobAuth), JsonNode::class.java,
            )
        }
        // (b) no tenantId → 200 (service auto-scope to demo-rp)
        val auditOwn = http.exchange(
            url("/admin/api/audit"), HttpMethod.GET,
            HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(auditOwn.statusCode.is2xxSuccessful)
            .`as`("bob GET /audit (no param) must succeed (auto-scope to demo-rp)")
            .isTrue()

        // ── 15. GET /tenants/{my}/aaguid-policy → 200 ────────────────────────
        // demo-rp policy row is seeded by resetState(); own-tenant access must succeed.
        val aaguidGetMy = http.exchange(
            url("/admin/api/tenants/$myTenantId/aaguid-policy"),
            HttpMethod.GET, HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(aaguidGetMy.statusCode.is2xxSuccessful)
            .`as`("bob GET own tenant aaguid-policy must succeed")
            .isTrue()

        // ── 16. GET /tenants/{other}/aaguid-policy → 403 ─────────────────────
        // tenantAId is a different tenant — cross-tenant read must be blocked.
        assertForbidden {
            http.exchange(
                url("/admin/api/tenants/$tenantAId/aaguid-policy"),
                HttpMethod.GET, HttpEntity<Any>(bobAuth), JsonNode::class.java,
            )
        }

        // ── 17. PUT /tenants/{my}/aaguid-policy → 200 ────────────────────────
        // Minimal valid UpdateRequest: mode=ANY, mdsStrict=false, entries=[].
        val aaguidUpdateBody = mapOf(
            "mode" to "ANY",
            "mdsStrict" to false,
            "entries" to listOf<Any>(),
        )
        val aaguidPutMy = http.exchange(
            url("/admin/api/tenants/$myTenantId/aaguid-policy"),
            HttpMethod.PUT,
            HttpEntity(om.writeValueAsString(aaguidUpdateBody), bobAuth),
            JsonNode::class.java,
        )
        assertThat(aaguidPutMy.statusCode.is2xxSuccessful)
            .`as`("bob PUT own tenant aaguid-policy must succeed")
            .isTrue()

        // ── 18. PUT /tenants/{other}/aaguid-policy → 403 ─────────────────────
        // Cross-tenant mutation must be blocked — this is the critical security assertion.
        val aaguidUpdateJson = om.writeValueAsString(aaguidUpdateBody)
        assertForbidden {
            http.exchange(
                url("/admin/api/tenants/$tenantAId/aaguid-policy"),
                HttpMethod.PUT,
                HttpEntity(aaguidUpdateJson, bobAuth),
                JsonNode::class.java,
            )
        }
    }
}
