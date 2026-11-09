package com.crosscert.passkey.admin.activity

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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource

/**
 * T7 — ActivityControllerIT: PLATFORM_OPERATOR Activity 페이지 회귀 채널 (자동 IT 1/2).
 *
 * alice (PLATFORM_OPERATOR) 로 단일 시나리오 5 assertion. KPI / Top5 /
 * sinceId polling 정상 동작 보장 — service boundary 또는 repository tuple
 * WHERE 가 깨지면 즉시 회귀 탐지.
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
class ActivityControllerIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (identical to RpAdminBoundaryIT / PlatformOperatorUnrestrictedIT)
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
                poolName = "activity-controller-it-owner"
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

    // ----------------------------------------------------------------
    // State reset — alice stays PLATFORM_OPERATOR, bob stays RP_ADMIN(demo-rp).
    // Mirrors the FK-safe pattern from RpAdminBoundaryIT (V23 fk_admin_user_tenant
    // blocks DELETE FROM tenant while child rows exist).
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
        val redisConn = redisFactory.connection
        try {
            redisConn.serverCommands().flushAll()
        } finally {
            redisConn.close()
        }
    }

    // ----------------------------------------------------------------
    // Login helpers (inlined from RpAdminBoundaryIT.loginAs)
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

    /**
     * Triggers the `AuthenticationFailureHandler` path so an
     * ADMIN_LOGIN_FAILED audit row is appended. Uses TestRestTemplate (not the
     * field `http`) so the 401 response body doesn't throw.
     */
    private fun attemptLoginFailure(email: String, wrongPassword: String) {
        val jar: MutableMap<String, String> = LinkedHashMap()
        val seed = rest.exchange(
            url("/admin/api/me"), HttpMethod.GET,
            HttpEntity<Any>(HttpHeaders()), String::class.java,
        )
        mergeSetCookiesByName(jar, seed.headers[HttpHeaders.SET_COOKIE])

        val h = HttpHeaders()
        h.contentType = MediaType.APPLICATION_FORM_URLENCODED
        h.set(HttpHeaders.COOKIE, renderCookieHeader(jar))
        h.set("X-XSRF-TOKEN", jar["XSRF-TOKEN"])
        val body = "email=$email&password=$wrongPassword"
        val resp = rest.exchange(
            url("/admin/login"), HttpMethod.POST,
            HttpEntity(body, h), String::class.java,
        )
        assertThat(resp.statusCode.value())
            .`as`("attempt login %s expected 401, got %s", email, resp.statusCode)
            .isEqualTo(401)
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

    private fun issueApiKey(auth: HttpHeaders, tenantId: String, name: String) {
        val body = mapOf(
            "tenantId" to tenantId,
            "name" to name,
            "scopes" to listOf("registration", "authentication"),
        )
        val res = rest.exchange(
            url("/admin/api/api-keys"),
            HttpMethod.POST,
            HttpEntity(om.writeValueAsString(body), auth),
            JsonNode::class.java,
        )
        assertThat(res.statusCode.is2xxSuccessful)
            .`as`("issue api-key tenant=%s: %s body=%s", tenantId, res.statusCode, res.body)
            .isTrue()
    }

    // ----------------------------------------------------------------
    // Test — single scenario, 5 assertion groups
    // ----------------------------------------------------------------

    @Test
    fun activitySnapshotKpiTop5Feed() {
        // ─── 1. alice (PLATFORM_OPERATOR) seeds activity ────────────────────
        // - 2 tenants (TENANT_CREATE x2) — each row carries tenant_id
        // - 1 api-key on tenant-a (API_KEY_ISSUE)
        // - alice's own login (ADMIN_LOGIN, tenant_id=null)
        // - bob wrong-pw attempt (ADMIN_LOGIN_FAILED, tenant_id=null)
        // → 5+ audit rows in the 24h window, 3+ ops, 1+ security.
        val aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw")
        val tenantAId = createTenantViaApi(aliceAuth, "activity-it-a", "Tenant A")
        createTenantViaApi(aliceAuth, "activity-it-b", "Tenant B")
        issueApiKey(aliceAuth, tenantAId, "activity-key-a")

        attemptLoginFailure("bob@crosscert.com", "wrong-pw-xyz")

        // ─── 2. GET /admin/api/activity ────────────────────────────────────
        val resp1 = rest.exchange(
            url("/admin/api/activity"), HttpMethod.GET,
            HttpEntity<Any>(aliceAuth), JsonNode::class.java,
        )
        assertThat(resp1.statusCode.is2xxSuccessful)
            .`as`("GET /admin/api/activity: %s body=%s", resp1.statusCode, resp1.body)
            .isTrue()
        val data = checkNotNull(resp1.body).get("data")

        // ── Assertion 1: KPI counts + p95Ms null ─────────────────────────
        val kpi = data.get("kpi")
        assertThat(kpi.get("events24h").asLong())
            .`as`("events24h must cover all seeded rows: %s", data)
            .isGreaterThanOrEqualTo(5L)
        assertThat(kpi.get("ops24h").asLong())
            .`as`("ops24h covers TENANT_CREATE x2 + API_KEY_ISSUE + ADMIN_LOGIN")
            .isGreaterThanOrEqualTo(3L)
        assertThat(kpi.get("security24h").asLong())
            .`as`("security24h covers ADMIN_LOGIN_FAILED")
            .isGreaterThanOrEqualTo(1L)
        // p95Ms is null → Jackson default-property-inclusion: non_null omits it.
        val p95 = kpi.path("p95Ms")
        assertThat(p95.isNull || p95.isMissingNode)
            .`as`("p95Ms must be absent/null (no Micrometer instrumentation yet): %s", kpi)
            .isTrue()

        // ── Assertion 2: Top5 contains tenant-a, excludes NULL-tenant rows ─
        val top5 = data.get("top5")
        assertThat(top5.isArray).isTrue()
        var tenantAFound = false
        for (t in top5) {
            // None of the top5 entries may have null tenantId (NULL rows are
            // filtered by ActivityRepository.topTenantsSinceRaw, and Jackson
            // non_null serialization would omit the field if it were null).
            val tidNode = t.path("tenantId")
            assertThat(tidNode.isMissingNode || tidNode.isNull)
                .`as`("top5 must not contain NULL-tenant rows: %s", t)
                .isFalse()
            if (tenantAId == tidNode.asString()) {
                tenantAFound = true
            }
        }
        assertThat(tenantAFound)
            .`as`("top5 must contain tenant-a (it had TENANT_CREATE + API_KEY_ISSUE): %s", top5)
            .isTrue()

        // ── Assertion 3: Feed sorted DESC by (createdAt, id) ─────────────
        val feed = data.get("feed")
        assertThat(feed.size())
            .`as`("feed must have at least 5 rows: %s", feed)
            .isGreaterThanOrEqualTo(5)
        for (i in 1 until feed.size()) {
            val prev = feed.get(i - 1).get("createdAt").asString()
            val curr = feed.get(i).get("createdAt").asString()
            assertThat(prev.compareTo(curr))
                .`as`(
                    "feed[%d].createdAt=%s should be >= feed[%d].createdAt=%s",
                    i - 1, prev, i, curr,
                )
                .isGreaterThanOrEqualTo(0)
        }

        // ─── 3. Polling with sinceId={feed[0].id} → empty feed ──────────────
        val firstId = feed.get(0).get("id").asString()
        val resp2 = rest.exchange(
            url("/admin/api/activity?sinceId=$firstId"), HttpMethod.GET,
            HttpEntity<Any>(aliceAuth), JsonNode::class.java,
        )
        assertThat(resp2.statusCode.is2xxSuccessful).isTrue()
        val feed2 = checkNotNull(resp2.body).get("data").get("feed")

        // ── Assertion 4: sinceId=newest → empty feed (idempotent polling) ─
        assertThat(feed2.size())
            .`as`("feed with sinceId=newest must be empty (tuple WHERE works): %s", feed2)
            .isZero()

        // ─── 4. bob normal login (new ADMIN_LOGIN row) → poll picks it up ──
        loginAs("bob@crosscert.com", "bob-temp-pw")
        val resp3 = rest.exchange(
            url("/admin/api/activity?sinceId=$firstId"), HttpMethod.GET,
            HttpEntity<Any>(aliceAuth), JsonNode::class.java,
        )
        assertThat(resp3.statusCode.is2xxSuccessful).isTrue()
        val feed3 = checkNotNull(resp3.body).get("data").get("feed")

        // ── Assertion 5: New events after sinceId visible to poll ────────
        assertThat(feed3.size())
            .`as`("new ADMIN_LOGIN row should appear when polling with old sinceId: %s", feed3)
            .isGreaterThanOrEqualTo(1)
    }

    /**
     * F09 회귀 채널 — feed slug 를 findAllById 배치로 전환해도 삭제된 테넌트(조회 결과에
     * 포함되지 않는 tenant_id)는 여전히 "(deleted)" 로 표기되어야 한다.
     *
     * resetState 는 demo-rp(...C0DE) 단 하나의 테넌트만 시드한다. tenant_id =
     * ...DEAD 인 audit_log 행을 직접 삽입하면 그 행의 테넌트는 존재하지 않으므로,
     * findAllById 결과 Map 에 들어오지 않는다 → getOrDefault fallback 검증.
     */
    @Test
    fun feed_deletedTenant_slugIsDeletedFallback() {
        ownerJdbc().update(
            """
            INSERT INTO PSK_APP_OWNER.audit_log
                (id, prev_hash, hash, actor_id, actor_email, action,
                 target_type, target_id, payload, created_at, updated_at,
                 tenant_id, tenant_prev_hash, tenant_hash)
            VALUES (HEXTORAW('0000000000000000000000000000DA03'), NULL, SYS_GUID(), NULL, 'admin@acme.com',
                 'API_KEY_ISSUE', 'API_KEY', 'pk_dead', '{}',
                 SYSTIMESTAMP, SYSTIMESTAMP,
                 HEXTORAW('0000000000000000000000000000DEAD'), NULL, SYS_GUID())
            """.trimIndent(),
        )

        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")
        val resp = rest.exchange(
            url("/admin/api/activity"), HttpMethod.GET,
            HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(resp.statusCode.is2xxSuccessful)
            .`as`("GET /admin/api/activity: %s body=%s", resp.statusCode, resp.body)
            .isTrue()

        val feed = checkNotNull(resp.body).get("data").get("feed")
        var deadEvent: JsonNode? = null
        for (e in feed) {
            if ("00000000-0000-0000-0000-00000000dead" == e.path("tenantId").asString()) {
                deadEvent = e
                break
            }
        }
        assertThat(deadEvent)
            .`as`("feed must contain the row referencing the deleted tenant (...DEAD): %s", feed)
            .isNotNull()
        assertThat(checkNotNull(deadEvent).get("tenantSlug").asString())
            .`as`(
                "deleted-tenant feed row must keep the \"(deleted)\" slug fallback after findAllById batch: %s",
                deadEvent,
            )
            .isEqualTo("(deleted)")
    }

    // ----------------------------------------------------------------
    // detail — GET /admin/api/activity/{id}
    // ----------------------------------------------------------------

    @Test
    fun detail_returnsPayload_forPlatformOperator() {
        // demo-rp(...C0DE)에 payload 있는 행 1건. RAW 0000...DA01 = UUID 00000000-0000-0000-0000-0000000000da01
        ownerJdbc().update(
            """
            INSERT INTO PSK_APP_OWNER.audit_log
                (id, prev_hash, hash, actor_id, actor_email, action,
                 target_type, target_id, payload, created_at, updated_at,
                 tenant_id, tenant_prev_hash, tenant_hash)
            VALUES (HEXTORAW('0000000000000000000000000000DA01'), NULL, SYS_GUID(), NULL, 'admin@acme.com',
                 'WEBAUTHN_CONFIG_UPDATED', 'TENANT', 'demo-rp',
                 '{"before":{"uv":false},"after":{"uv":true}}',
                 SYSTIMESTAMP, SYSTIMESTAMP,
                 HEXTORAW('0000000000000000000000000000C0DE'), NULL, SYS_GUID())
            """.trimIndent(),
        )

        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")
        val resp = rest.exchange(
            url("/admin/api/activity/00000000-0000-0000-0000-00000000da01"),
            HttpMethod.GET, HttpEntity<Any>(auth), String::class.java,
        )

        assertThat(resp.statusCode.is2xxSuccessful).isTrue()
        val body = readActivityJson(resp.body)
        assertThat(body.get("action").asString()).isEqualTo("WEBAUTHN_CONFIG_UPDATED")
        assertThat(body.get("payload").asString()).contains("\"after\"")
        assertThat(body.get("tenantSlug").asString()).isEqualTo("demo-rp")
    }

    @Test
    fun detail_isForbidden_forRpAdmin() {
        ownerJdbc().update(
            """
            INSERT INTO PSK_APP_OWNER.audit_log
                (id, prev_hash, hash, actor_id, actor_email, action,
                 target_type, target_id, payload, created_at, updated_at,
                 tenant_id, tenant_prev_hash, tenant_hash)
            VALUES (HEXTORAW('0000000000000000000000000000DA02'), NULL, SYS_GUID(), NULL, 'x@x.com', 'API_KEY_ISSUE',
                 'API_KEY', 'pk_x', '{}', SYSTIMESTAMP, SYSTIMESTAMP,
                 HEXTORAW('0000000000000000000000000000C0DE'), NULL, SYS_GUID())
            """.trimIndent(),
        )
        val auth = loginAs("bob@crosscert.com", "bob-temp-pw")
        val resp = rest.exchange(
            url("/admin/api/activity/00000000-0000-0000-0000-00000000da02"),
            HttpMethod.GET, HttpEntity<Any>(auth), String::class.java,
        )
        assertThat(resp.statusCode.value()).isEqualTo(403)
    }

    @Test
    fun detail_isNotFound_forUnknownId() {
        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")
        val resp = rest.exchange(
            url("/admin/api/activity/00000000-0000-0000-0000-00000000dead"),
            HttpMethod.GET, HttpEntity<Any>(auth), String::class.java,
        )
        assertThat(resp.statusCode.value()).isEqualTo(404)
    }

    /** ApiResponse envelope이면 data를 벗기고, 아니면 root 반환. */
    private fun readActivityJson(body: String?): JsonNode {
        try {
            val root = om.readTree(body)
            return if (root.has("data")) root.get("data") else root
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
