package com.crosscert.passkey.admin.policy

import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.JsonNode

/**
 * Phase F1 Task 6 (Gap #20) — SecurityPolicy round-trip smoke IT.
 *
 * Single happy-path test: GET (seeded defaults from V31) → PUT (new values)
 * → GET (values persisted). RBAC + validation are covered by the manual smoke
 * at Phase F1 Task 17, per execution policy §1 (tests minimal).
 *
 * Self-contained Testcontainers scaffolding mirroring `AdminFlowIT`
 * and `AaguidPolicyCeremonyIT` — no shared base class exists in this
 * module by design.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SecurityPolicyIT {

    companion object {
        // ------------------------------------------------------------
        // Containers (verbatim from AdminFlowIT)
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

        /**
         * Parse each Set-Cookie header's name=value pair and upsert it into
         * the cookie jar. Later Set-Cookie values for the same cookie name
         * replace earlier ones — matching browser semantics and avoiding
         * the "two XSRF-TOKEN cookies in the same Cookie header" hazard
         * that codex P1 flagged.
         */
        private fun mergeSetCookiesByName(jar: MutableMap<String, String>, setCookies: List<String>?) {
            if (setCookies == null) return
            for (sc in setCookies) {
                val pair = sc.split(";", limit = 2)[0]
                val eq = pair.indexOf('=')
                if (eq < 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1)
                if (name.isEmpty()) continue
                // Empty value = server-sent cookie delete. Remove from jar.
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

    private fun url(path: String): String = "http://localhost:$port$path"

    // ------------------------------------------------------------
    // Login + cookie helpers (verbatim from AdminFlowIT)
    // ------------------------------------------------------------

    /**
     * Performs Spring Security form login and returns an HttpHeaders
     * pre-populated with the session cookie + CSRF token, suitable for
     * subsequent JSON API calls.
     *
     * Flow:
     *  1. Seed: GET /admin/api/me to acquire XSRF-TOKEN cookie.
     *     AdminSecurityConfig sets `setCsrfRequestAttributeName(null)`
     *     so the CSRF cookie is emitted on every response — including
     *     the 401 we get here (no auth).
     *  2. POST /admin/login as form data, sending the XSRF cookie back
     *     in both Cookie header (CookieCsrfTokenRepository validates
     *     cookie==header) and X-XSRF-TOKEN header.
     *  3. Replace cookies by name (not append) so that a rotated
     *     XSRF-TOKEN on login does not leave two same-named cookies
     *     in the outgoing Cookie header — servers don't all agree on
     *     which one "wins", and CookieCsrfTokenRepository would reject
     *     a mismatch between Cookie and X-XSRF-TOKEN header.
     *  4. Post-login "warm-up" GET /admin/api/me with the session
     *     cookie. This forces the server to emit a fresh CSRF cookie
     *     for the authenticated session (in case Spring's
     *     CsrfAuthenticationStrategy did not rotate during the
     *     login POST), guaranteeing the returned headers are ready
     *     for state-changing JSON calls.
     */
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

        // 3. Warm-up GET /me with the authenticated session cookie.
        //    Spring's default CsrfAuthenticationStrategy may rotate the
        //    XSRF token at login, but in any case this round-trip
        //    guarantees the cookie + header pair we return matches the
        //    server's authoritative session token.
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
    fun getThenPutThenGetRoundtrip() {
        // Alice = PLATFORM_OPERATOR (V11 seed; V23 role rename) — can PUT.
        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")
        auth.contentType = MediaType.APPLICATION_JSON

        // 1. GET — seeded defaults from V31 (idle=30, mfa=Y, cors=[]).
        val initial = rest.exchange(
            url("/admin/api/security-policy"),
            HttpMethod.GET, HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(initial.statusCode.value())
            .`as`("initial GET: %s body=%s", initial.statusCode, initial.body)
            .isEqualTo(200)
        assertThat(checkNotNull(checkNotNull(initial.body).get("sessionIdleTimeoutMinutes")).asInt()).isEqualTo(30)

        // 2. PUT — new values.
        val body: MutableMap<String, Any> = LinkedHashMap()
        body["sessionIdleTimeoutMinutes"] = 15
        body["mfaRequired"] = false
        body["corsAllowlist"] = listOf("https://a.example.com")
        val putRes = rest.exchange(
            url("/admin/api/security-policy"),
            HttpMethod.PUT, HttpEntity(body, auth), JsonNode::class.java,
        )
        assertThat(putRes.statusCode.value())
            .`as`("PUT: %s body=%s", putRes.statusCode, putRes.body)
            .isEqualTo(200)

        // 3. GET — values are persisted.
        val readback = rest.exchange(
            url("/admin/api/security-policy"),
            HttpMethod.GET, HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(readback.statusCode.value()).isEqualTo(200)
        val readbackBody = checkNotNull(readback.body)
        assertThat(checkNotNull(readbackBody.get("sessionIdleTimeoutMinutes")).asInt()).isEqualTo(15)
        assertThat(checkNotNull(readbackBody.get("mfaRequired")).asBoolean()).isFalse()
        val cors = checkNotNull(readbackBody.get("corsAllowlist"))
        assertThat(cors.isArray).isTrue()
        assertThat(cors).hasSize(1)
        assertThat(checkNotNull(cors.get(0)).asString()).isEqualTo("https://a.example.com")
    }
}
