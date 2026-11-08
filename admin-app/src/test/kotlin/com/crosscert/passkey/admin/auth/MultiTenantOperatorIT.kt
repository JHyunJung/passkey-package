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
import java.util.TreeSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Task 12 — MultiTenantOperatorIT: 멀티 RP 운영자 스위처·격리 통합 테스트.
 *
 * bob (RP_ADMIN) 을 demo-rp 와 second-rp 두 테넌트에 매핑한 뒤 6단계 시나리오를 검증한다:
 *  1. 로그인 후 `GET /admin/api/active-tenant` → `allowedTenantIds` 크기 = 2.
 *  2. 초기 `activeTenantId` 는 두 UUID 중 정렬상 첫 번째(TreeSet.first()).
 *  3. `POST /admin/api/active-tenant {secondRp}` → 200, `activeTenantId` = secondRp.
 *  4. 활성 RP = secondRp 일 때 `GET /admin/api/tenants` 가 secondRp 의 slug 만 반환
 *     — Hibernate `@Filter` 격리 확인.
 *  5. `POST /admin/api/active-tenant {randomUnmappedUuid}` → 403 ACCESS_DENIED.
 *  6. `GET /admin/api/tenants/{demo-rp-id}` — secondRp 활성 중에 demo-rp 직접 접근 → 403.
 *
 * Testcontainers (Oracle XE 21 + Redis 7) 및 loginAs 패턴은 `AdminFlowIT` 과 동일.
 */
// classes 명시: auth 패키지에 MfaController*Test$SliceConfig(@SpringBootConfiguration) 2개가
// 있어 config 자동탐색이 "multiple @SpringBootConfiguration" 으로 깨진다(단독 실행 시). 메인 앱
// 클래스를 직접 지정해 자동탐색을 끈다(AdminFlowIT 은 root 패키지라 자동탐색이 바로 AdminApplication 을 찾음).
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AdminApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Testcontainers
class MultiTenantOperatorIT {

    companion object {
        // ----------------------------------------------------------------
        // Constants
        // ----------------------------------------------------------------

        /** demo-rp 테넌트 ID (V23 / resetState 시드). HEXTORAW('0000000000000000000000000000C0DE'). */
        private val DEMO_RP_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000C0DE")

        /**
         * second-rp 테넌트 ID.  UUID 값을 demo-rp(…C0DE) 보다 크게 잡아서
         * TreeSet.first() == demo-rp 임이 결정적이 되도록 한다.
         * 0000…C0DF > 0000…C0DE → demo-rp 가 정렬 첫 번째.
         * HEXTORAW('0000000000000000000000000000C0DF').
         */
        private val SECOND_RP_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000C0DF")

        // ----------------------------------------------------------------
        // Containers
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
            reg.add("spring.flyway.url", ORACLE::getJdbcUrl)
            reg.add("spring.flyway.user") { "PSK_APP_OWNER" }
            reg.add("spring.flyway.password") { SYS_PASSWORD }
            reg.add("spring.data.redis.host", REDIS::getHost)
            reg.add("spring.data.redis.port") { REDIS.getMappedPort(6379) }
        }

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
                poolName = "multi-tenant-it-owner"
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
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var ds: DataSource

    @Autowired
    lateinit var redisFactory: RedisConnectionFactory

    lateinit var jdbc: JdbcTemplate

    // ----------------------------------------------------------------
    // State reset
    // ----------------------------------------------------------------

    /**
     * 매 테스트 전 깨끗한 상태로 초기화한다.
     * AdminFlowIT.resetState() 의 FK 순서 패턴을 그대로 따른다.
     *  - demo-rp (0x…C0DE) 재시드
     *  - second-rp (0x…C0DF) 시드 — 두 번째 매핑용
     *  - bob 을 RP_ADMIN + 두 테넌트에 매핑
     */
    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)

        // 감사 로그: PSK_APP_ADMIN 은 DELETE 권한 없음(V10 설계) → 스키마 소유자 풀 사용
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key")
        jdbc.update("DELETE FROM PSK_APP_OWNER.credential")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy_entry")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format")
        // FK: admin_user_tenant.tenant_id → tenant
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant")
        jdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role <> 'PLATFORM_OPERATOR'")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant")

        // ── demo-rp (0x…C0DE) 재시드 ──────────────────────────────────────
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
            VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'),
                'http://localhost:9090', 0)
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
        // AAGUID 정책 기본값 (리스트 엔드포인트에 필요)
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_aaguid_policy
                (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
            VALUES (HEXTORAW('0000000000000000000000000000C0DE'),
                'ANY', 'N', SYSTIMESTAMP, SYSTIMESTAMP, 'test:reset')
            """.trimIndent(),
        )

        // ── second-rp (0x…C0DF) 시드 ──────────────────────────────────────
        // UUID 0000…C0DF > 0000…C0DE → TreeSet.first() == demo-rp (결정적 기본값)
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                require_user_verification, mds_required, created_at, updated_at)
            VALUES (HEXTORAW('0000000000000000000000000000C0DF'),
                'second-rp', 'Second RP', 'second.localhost', 'Second RP', 'active', 'Y', 'N',
                SYSTIMESTAMP, SYSTIMESTAMP)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
            VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DF'),
                'http://localhost:9091', 0)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format)
            VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DF'), 'none')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_aaguid_policy
                (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
            VALUES (HEXTORAW('0000000000000000000000000000C0DF'),
                'ANY', 'N', SYSTIMESTAMP, SYSTIMESTAMP, 'test:reset')
            """.trimIndent(),
        )

        // ── bob = RP_ADMIN, 두 테넌트에 매핑 ─────────────────────────────
        jdbc.update(
            """
            UPDATE PSK_APP_OWNER.admin_user
               SET role = 'RP_ADMIN'
             WHERE email = 'bob@crosscert.com'
            """.trimIndent(),
        )
        // 매핑 1: bob → demo-rp
        jdbc.update(
            """
            MERGE INTO PSK_APP_OWNER.admin_user_tenant t
            USING (SELECT HEXTORAW('00000000000000000000000000000011') AS aid,
                          HEXTORAW('0000000000000000000000000000C0DE') AS tid FROM dual) s
               ON (t.admin_user_id = s.aid AND t.tenant_id = s.tid)
             WHEN NOT MATCHED THEN
               INSERT (admin_user_id, tenant_id, created_at, created_by)
               VALUES (s.aid, s.tid, SYSTIMESTAMP, 'test:reset')
            """.trimIndent(),
        )
        // 매핑 2: bob → second-rp
        jdbc.update(
            """
            MERGE INTO PSK_APP_OWNER.admin_user_tenant t
            USING (SELECT HEXTORAW('00000000000000000000000000000011') AS aid,
                          HEXTORAW('0000000000000000000000000000C0DF') AS tid FROM dual) s
               ON (t.admin_user_id = s.aid AND t.tenant_id = s.tid)
             WHEN NOT MATCHED THEN
               INSERT (admin_user_id, tenant_id, created_at, created_by)
               VALUES (s.aid, s.tid, SYSTIMESTAMP, 'test:reset')
            """.trimIndent(),
        )

        // Redis 세션 초기화
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun url(path: String): String = "http://localhost:$port$path"

    /** TestRestTemplate 은 4xx 를 삼킨다 — 403 단언에는 plain RestTemplate 사용. */
    private val http = RestTemplate()

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
    // Test
    // ----------------------------------------------------------------

    /**
     * 멀티 RP 운영자(bob) 스위처·격리 6단계 시나리오.
     *
     * **단계 요약:**
     *  1. bob 로그인 후 GET /admin/api/active-tenant → allowedTenantIds 2개.
     *  2. 초기 activeTenantId == TreeSet.first() (두 UUID 중 작은 쪽 = demo-rp(0x…C0DE)).
     *  3. POST /admin/api/active-tenant {second-rp} → 200, activeTenantId = second-rp.
     *  4. GET /admin/api/tenants → second-rp 의 slug 만 반환 (@Filter 격리).
     *  5. POST /admin/api/active-tenant {randomUnmapped} → 403.
     *  6. GET /admin/api/tenants/{demo-rp-id} — second-rp 활성 중에 demo-rp 직접 접근 → 403.
     */
    @Test
    fun multiTenantOperatorCanSwitchAndIsIsolated() {
        // ── 전제: demo-rp < second-rp (UUID 자연 정렬) ───────────────────────
        // TreeSet<UUID>.first() 는 자연 순서상 가장 작은 UUID 를 반환한다.
        // 0000…C0DE < 0000…C0DF 이므로 demo-rp 가 정렬 첫 번째.
        val sortedAllowed = TreeSet<UUID>()
        sortedAllowed.add(DEMO_RP_ID)
        sortedAllowed.add(SECOND_RP_ID)
        val expectedDefault = sortedAllowed.first() // demo-rp (0x…C0DE)
        assertThat(expectedDefault)
            .`as`("전제: demo-rp 가 정렬 첫 번째여야 함")
            .isEqualTo(DEMO_RP_ID)

        // ── 1. bob 로그인 ─────────────────────────────────────────────────
        val bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw")

        // ── 2. GET /admin/api/active-tenant → allowedTenantIds 2개 ────────
        //    ActiveTenantController 는 ApiResponse 래퍼 없이 ActiveTenantView 를 직접 반환.
        val getTenant = rest.exchange(
            url("/admin/api/active-tenant"), HttpMethod.GET,
            HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(getTenant.statusCode.is2xxSuccessful)
            .`as`("GET active-tenant must succeed: %s", getTenant.statusCode)
            .isTrue()
        val view = getTenant.body
        assertThat(view).isNotNull()
        checkNotNull(view)

        val allowedIds = view.get("allowedTenantIds").values().map { it.asString() }
        assertThat(allowedIds)
            .`as`("bob 의 allowedTenantIds 는 2개여야 함: %s", allowedIds)
            .hasSize(2)

        // ── 3. 초기 activeTenantId == demo-rp (정렬 첫 번째) ────────────
        val initialActive = view.get("activeTenantId").asString()
        assertThat(UUID.fromString(initialActive))
            .`as`("초기 activeTenantId 는 정렬 첫 번째 UUID (demo-rp) 여야 함")
            .isEqualTo(expectedDefault)

        // ── 4. POST active-tenant {second-rp} → 200, activeTenantId = second-rp ──
        val switchBody = "{\"tenantId\":\"$SECOND_RP_ID\"}"
        val switchRes = rest.exchange(
            url("/admin/api/active-tenant"), HttpMethod.POST,
            HttpEntity(switchBody, bobAuth), JsonNode::class.java,
        )
        assertThat(switchRes.statusCode.is2xxSuccessful)
            .`as`(
                "POST active-tenant (second-rp) must succeed: %s body=%s",
                switchRes.statusCode, switchRes.body,
            )
            .isTrue()
        val switchView = checkNotNull(switchRes.body)
        assertThat(UUID.fromString(switchView.get("activeTenantId").asString()))
            .`as`("전환 후 activeTenantId 는 second-rp 여야 함")
            .isEqualTo(SECOND_RP_ID)

        // ── 5. @Filter 격리: GET /admin/api/tenants → second-rp 만 반환 ──
        //    TenantAdminService.list() 는 currentTenantScope() 을 통해
        //    활성 RP 의 테넌트만 반환한다.
        val listRes = rest.exchange(
            url("/admin/api/tenants"), HttpMethod.GET,
            HttpEntity<Any>(bobAuth), JsonNode::class.java,
        )
        assertThat(listRes.statusCode.is2xxSuccessful)
            .`as`("GET /tenants 는 성공해야 함: %s", listRes.statusCode)
            .isTrue()
        val listData = checkNotNull(listRes.body).get("data")
        val slugs = listData.values().map { n -> n.get("slug").asString() }
        assertThat(slugs)
            .`as`("second-rp 활성 중 tenant 목록은 second-rp 만 포함해야 함 — 실제: %s", slugs)
            .containsExactly("second-rp")
        assertThat(slugs)
            .`as`("second-rp 활성 중 demo-rp 는 목록에 없어야 함")
            .doesNotContain("demo-rp")

        // ── 6. POST active-tenant {unmapped UUID} → 403 ACCESS_DENIED ────
        val unmapped = UUID.randomUUID()
        val unmappedBody = "{\"tenantId\":\"$unmapped\"}"
        assertThatThrownBy {
            http.exchange(
                url("/admin/api/active-tenant"), HttpMethod.POST,
                HttpEntity(unmappedBody, bobAuth), JsonNode::class.java,
            )
        }
            .`as`("허용 외 UUID 로 전환 시도 → 403")
            .isInstanceOf(HttpClientErrorException::class.java)
            .satisfies(
                ThrowingConsumer { e ->
                    assertThat((e as HttpClientErrorException).statusCode.value())
                        .isEqualTo(403)
                },
            )

        // ── 7. assertCanAccessTenant 경유 — bob 의 allowedTenantIds 밖 테넌트 직접 접근 → 403 ──
        //    GET /admin/api/tenants/{id} → TenantAdminService.get() → assertCanAccessTenant() 호출.
        //    TenantBoundary.assertCanAccessTenant 는 allowedTenantIds 범위로 검사한다(활성 RP 무관).
        //    alice 가 만든 external-rp 는 bob 의 allowedTenantIds 에 없으므로 → 403.
        //    alice (PLATFORM_OPERATOR) 로 로그인하여 외부 테넌트 생성
        val aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw")
        val externalTenantBody = """
            {"slug":"external-rp","displayName":"External RP","rpId":"external.localhost",
             "rpName":"External RP",
             "allowedOrigins":["http://localhost:9092"],
             "acceptedFormats":["none"],
             "requireUserVerification":true,
             "mdsRequired":false,
             "attestationConveyance":"NONE",
             "webauthnTimeoutMs":60000}
        """.trimIndent()
        val createExternal = rest.exchange(
            url("/admin/api/tenants"), HttpMethod.POST,
            HttpEntity(externalTenantBody, aliceAuth), JsonNode::class.java,
        )
        assertThat(createExternal.statusCode.is2xxSuccessful)
            .`as`(
                "alice 가 외부 테넌트 생성 → 201: %s body=%s",
                createExternal.statusCode, createExternal.body,
            )
            .isTrue()
        val externalTenantId = checkNotNull(createExternal.body).get("data").get("id").asString()

        // bob 세션은 유지 — second-rp 가 여전히 활성 상태.
        // external-rp 는 bob 의 allowedTenantIds 에 없으므로 assertCanAccessTenant → 403.
        assertThatThrownBy {
            http.exchange(
                url("/admin/api/tenants/$externalTenantId"), HttpMethod.GET,
                HttpEntity<Any>(bobAuth), JsonNode::class.java,
            )
        }
            .`as`("bob 이 허용 외 테넌트(external-rp) 직접 접근 → 403")
            .isInstanceOf(HttpClientErrorException::class.java)
            .satisfies(
                ThrowingConsumer { e ->
                    assertThat((e as HttpClientErrorException).statusCode.value())
                        .isEqualTo(403)
                },
            )
    }
}
