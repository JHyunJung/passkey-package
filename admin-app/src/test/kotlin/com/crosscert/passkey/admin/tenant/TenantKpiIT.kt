package com.crosscert.passkey.admin.tenant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
import javax.sql.DataSource

/**
 * Phase F2 Task 5 (Gap #1/#2/#4) — TenantView KPI + webauthn fields smoke IT.
 *
 * Single happy-path test: GET /admin/api/tenants returns the 5 fields added
 * in commit 075bbf0 (`credentials`, `apiKeys`, `lastEventAt`,
 * `attestationConveyance`, `webauthnTimeoutMs`).
 *
 * RBAC, validation, and write semantics are covered elsewhere (existing
 * tenant ITs + manual smoke). Per execution policy §1: one round-trip only.
 *
 * Self-contained Testcontainers scaffolding mirroring `SecurityPolicyIT`
 * and `AdminFlowIT` — no shared base class exists in this module by design.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TenantKpiIT {

    companion object {
        // ------------------------------------------------------------
        // Containers (verbatim from SecurityPolicyIT)
        // ------------------------------------------------------------

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

    // ------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var ds: DataSource

    /**
     * 목록에 테넌트 한 건을 심는다. 예전엔 V11/V19 시드 마이그레이션이 테넌트를 넣었지만 Flyway squash 이후
     * 테넌트 시드는 db/local·db/dev 에만 있고 test 프로필(db/migration + db/testfix)엔 없다 — 다른 IT
     * (RpAdminBoundaryIT 등)처럼 직접 넣는다. attestation_conveyance / webauthn_timeout_ms 는 일부러 빼서
     * 아래 단언이 DB 기본값(NONE / 60000)을 검증하게 둔다.
     */
    @BeforeEach
    fun seedTenant() {
        JdbcTemplate(ds).update(
            """
            INSERT INTO PSK_APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                require_user_verification, mds_required, created_at, updated_at)
            SELECT HEXTORAW('0000000000000000000000000000C0DE'),
                'demo-rp', 'Demo RP', 'localhost', 'Demo RP', 'active', 'Y', 'N',
                SYSTIMESTAMP, SYSTIMESTAMP
              FROM dual
             WHERE NOT EXISTS (SELECT 1 FROM PSK_APP_OWNER.tenant WHERE slug = 'demo-rp')
            """.trimIndent(),
        )
    }

    private fun url(path: String): String = "http://localhost:$port$path"

    // ------------------------------------------------------------
    // Login + cookie helpers (verbatim from SecurityPolicyIT)
    // ------------------------------------------------------------

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

        // 3. Warm-up GET /me to lock the session+CSRF cookie pair.
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

    // ------------------------------------------------------------
    // The smoke scenario
    // ------------------------------------------------------------

    @Test
    fun getTenantsReturnsKpiAndWebauthnFields() {
        // Alice = PLATFORM_OPERATOR (V11 seed; V23 role rename) — sees all tenants.
        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")

        val listRes = rest.exchange(
            url("/admin/api/tenants"),
            HttpMethod.GET, HttpEntity<Any>(auth), JsonNode::class.java,
        )

        assertThat(listRes.statusCode.value())
            .`as`(
                "GET /admin/api/tenants: %s body=%s",
                listRes.statusCode, listRes.body,
            )
            .isEqualTo(200)

        val envelope = listRes.body
        assertThat(envelope).isNotNull()
        // ApiResponse<List<TenantView>> wraps the list in `data`.
        val list = checkNotNull(envelope).get("data")
        assertThat(list).`as`("ApiResponse.data array").isNotNull()
        assertThat(list.isArray).isTrue()
        assertThat(list).`as`("tenant list non-empty (seedTenant)").isNotEmpty()

        val first = list.get(0)
        // Commit 075bbf0 added 5 fields. Non-null fields must be present on
        // every tenant. `lastEventAt` is an `Instant` that may
        // legitimately be null when a tenant has no events yet — and the
        // project-wide `spring.jackson.default-property-inclusion:
        // non_null` (see core/application-common.yml) means Jackson omits
        // the key entirely in that case. The DTO field still exists; we
        // assert here that *if* present, it's typed as a string (Instant
        // serialization). Absence == "no events yet", which is the V11/V19
        // seed reality on a clean Testcontainers boot.
        assertThat(first.has("credentials")).`as`("credentials field").isTrue()
        assertThat(first.has("apiKeys")).`as`("apiKeys field").isTrue()
        assertThat(first.has("attestationConveyance")).`as`("attestationConveyance field").isTrue()
        assertThat(first.has("webauthnTimeoutMs")).`as`("webauthnTimeoutMs field").isTrue()
        if (first.has("lastEventAt")) {
            assertThat(first.get("lastEventAt").isString || first.get("lastEventAt").isNull)
                .`as`("lastEventAt — when present, must be Instant string or null")
                .isTrue()
        }

        // KPI counts are numeric (may be 0 — that's fine).
        assertThat(first.get("credentials").isNumber).isTrue()
        assertThat(first.get("apiKeys").isNumber).isTrue()

        // Webauthn fields carry the V33 defaults for seeded tenants.
        assertThat(first.get("attestationConveyance").asString()).isEqualTo("NONE")
        assertThat(first.get("webauthnTimeoutMs").asInt()).isEqualTo(60000)
    }
}
