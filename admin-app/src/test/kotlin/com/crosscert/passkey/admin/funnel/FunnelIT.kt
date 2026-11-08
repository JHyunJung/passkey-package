package com.crosscert.passkey.admin.funnel

import com.crosscert.passkey.core.ceremony.CeremonyAction
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
import java.nio.ByteBuffer
import java.util.UUID
import javax.sql.DataSource

/**
 * Phase F3 Task 4 (Gap #3/#9) — Funnel endpoint IT.
 *
 * Two scenarios over GET /admin/api/tenants/{id}/funnel?windowDays=7:
 *  1. [getFunnelReturnsShapeWithZeroOrMoreCounts] — the FunnelDto.View
 *     JSON SHAPE (windowDays / registration / authentication / conversion /
 *     series / byEventType) on a tenant with no seeded ceremony events.
 *  2. [getFunnelComputesRealCountsFromCeremonyEvent] — seeds real
 *     `ceremony_event` rows (V41) and asserts the COMPUTED counts:
 *     4 registration attempts, 3 successes, ratio 0.75.
 *
 * Self-contained Testcontainers scaffolding mirroring `TenantKpiIT`.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class FunnelIT {

    companion object {
        // ------------------------------------------------------------
        // Containers (verbatim from TenantKpiIT)
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
         * Convert a UUID to the 16-byte big-endian RAW representation used by Oracle.
         * Required when binding a UUID against a RAW(16) column via JDBC.
         */
        private fun uuidToBytes(id: UUID): ByteArray {
            val bb = ByteBuffer.allocate(16)
            bb.putLong(id.mostSignificantBits)
            bb.putLong(id.leastSignificantBits)
            return bb.array()
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

    private fun url(path: String): String = "http://localhost:$port$path"

    // ------------------------------------------------------------
    // The smoke scenario — JSON shape only (no seeded events)
    // ------------------------------------------------------------

    @Test
    fun getFunnelReturnsShapeWithZeroOrMoreCounts() {
        // Alice = PLATFORM_OPERATOR (test seed V9001; V23 role rename) — may create/see any tenant.
        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")

        // Production tenant seed was stripped (profile separation); only admin users are
        // restored in test (V9001). Create a fresh tenant so this IT is self-sufficient.
        val tenantId = createTenant(auth, "funnel-shape")
        val body = fetchFunnel(auth, tenantId, 7)

        // Shape + element-typed assertions. This tenant has no seeded ceremony
        // events, so counts are 0 — but field types must still be correct.
        assertThat(body.get("windowDays").asInt()).isEqualTo(7)

        val registration = body.get("registration")
        assertThat(registration).isNotNull()
        assertThat(registration.get("attempts").isNumber).isTrue()
        assertThat(registration.get("success").isNumber).isTrue()
        assertThat(registration.get("ratio").isNumber).isTrue()

        val authentication = body.get("authentication")
        assertThat(authentication).isNotNull()
        assertThat(authentication.get("attempts").isNumber).isTrue()
        assertThat(authentication.get("success").isNumber).isTrue()
        assertThat(authentication.get("ratio").isNumber).isTrue()

        assertThat(body.get("conversion").isNumber).isTrue()

        val series = body.get("series")
        assertThat(series).isNotNull()
        assertThat(series.isArray).isTrue()
        assertThat(series.size()).`as`("series length must equal windowDays").isEqualTo(7)
        for (point in series) {
            assertThat(point.get("day").isString).`as`("series[].day non-null text").isTrue()
            assertThat(point.get("attempts").isNumber).`as`("series[].attempts numeric").isTrue()
            assertThat(point.get("success").isNumber).`as`("series[].success numeric").isTrue()
        }

        val byEventType = body.get("byEventType")
        assertThat(byEventType).isNotNull()
        assertThat(byEventType.isArray).isTrue()
        // byEventType may be empty when no ceremony events exist; if any element
        // is present its shape must still be {type:string, n:number}.
        for (evt in byEventType) {
            assertThat(evt.get("type").isString).`as`("byEventType[].type non-null text").isTrue()
            assertThat(evt.get("n").isNumber).`as`("byEventType[].n numeric").isTrue()
        }
    }

    // ------------------------------------------------------------
    // Real-count scenario — seed ceremony_event, assert computed funnel
    // ------------------------------------------------------------

    @Test
    fun getFunnelComputesRealCountsFromCeremonyEvent() {
        val auth = loginAs("alice@crosscert.com", "alice-temp-pw")
        // Own tenant created here — guarantees no ceremony_event rows from other
        // tenants/tests skew the count assertions below.
        val tenantId = createTenant(auth, "funnel-counts")
        val tenant = UUID.fromString(tenantId)

        // Seed 4 begins + 3 successes within the 7-day window (SYSTIMESTAMP).
        // FunnelService maps registration.attempts <- REGISTRATION_BEGIN,
        // registration.success <- REGISTRATION_FINISH_OK ("FINISH_OK" string).
        seedCeremonyEvents(tenant, CeremonyAction.REGISTRATION_BEGIN, 4)
        seedCeremonyEvents(tenant, CeremonyAction.REGISTRATION_SUCCESS, 3)

        val body = fetchFunnel(auth, tenantId, 7)

        val registration = body.get("registration")
        assertThat(registration).`as`("registration stage present").isNotNull()
        assertThat(registration.get("attempts").asLong())
            .`as`("registration.attempts == 4 (REGISTRATION_BEGIN count)")
            .isEqualTo(4L)
        assertThat(registration.get("success").asLong())
            .`as`("registration.success == 3 (REGISTRATION_FINISH_OK count)")
            .isEqualTo(3L)
        assertThat(registration.get("ratio").asDouble())
            .`as`("registration.ratio == success/attempts == 3/4")
            .isEqualTo(0.75)
    }

    // ------------------------------------------------------------
    // Shared helpers (DRY across both scenarios)
    // ------------------------------------------------------------

    /**
     * Creates a tenant with the given slug as the authenticated admin and returns
     * its UUID. POST /admin/api/tenants returns ApiResponse&lt;TenantView&gt; (201);
     * the id lives at `data.id` (matches AdminFlowIT step ③).
     */
    private fun createTenant(auth: HttpHeaders, slug: String): String {
        val body = (
            """
            {"slug":"%s","displayName":"%s","rpId":"%s.example.com","rpName":"%s",
             "allowedOrigins":["http://localhost"],
             "acceptedFormats":["none","packed"],
             "requireUserVerification":true,
             "mdsRequired":false,
             "attestationConveyance":"NONE",
             "webauthnTimeoutMs":60000}
            """.trimIndent() + "\n"
            ).format(slug, slug, slug, slug)
        val create = rest.exchange(
            url("/admin/api/tenants"), HttpMethod.POST,
            HttpEntity(body, auth), JsonNode::class.java,
        )
        assertThat(create.statusCode.value())
            .`as`("create tenant %s: %s body=%s", slug, create.statusCode, create.body)
            .isEqualTo(201)
        val envelope = create.body
        assertThat(envelope).isNotNull()
        val data = checkNotNull(envelope).get("data")
        assertThat(data).`as`("create tenant ApiResponse.data").isNotNull()
        return data.get("id").asString()
    }

    /** GET the funnel for a tenant and assert 200; returns the FunnelDto.View body (no envelope). */
    private fun fetchFunnel(auth: HttpHeaders, tenantId: String, windowDays: Int): JsonNode {
        val funnel = rest.exchange(
            url("/admin/api/tenants/$tenantId/funnel?windowDays=$windowDays"),
            HttpMethod.GET, HttpEntity<Any>(auth), JsonNode::class.java,
        )
        assertThat(funnel.statusCode.value())
            .`as`(
                "GET /admin/api/tenants/%s/funnel: %s body=%s",
                tenantId, funnel.statusCode, funnel.body,
            )
            .isEqualTo(200)
        // FunnelController returns FunnelDto.View directly (no ApiResponse envelope —
        // matches AaguidPolicyController / SecurityPolicyController convention).
        val body = funnel.body
        assertThat(body).isNotNull()
        return checkNotNull(body)
    }

    /**
     * Inserts `count` ceremony_event rows for `tenant` with the given
     * action at SYSTIMESTAMP (within the funnel window). tenant_id is RAW(16) so
     * the UUID is bound as its 16-byte big-endian form ([uuidToBytes]).
     * id defaults to SYS_GUID(); created_at/updated_at are required (BaseEntity).
     */
    private fun seedCeremonyEvents(tenant: UUID, action: String, count: Int) {
        val jdbc = JdbcTemplate(ds)
        val tenantBytes = uuidToBytes(tenant)
        for (i in 0 until count) {
            jdbc.update(
                "INSERT INTO PSK_APP_OWNER.ceremony_event " +
                    "(tenant_id, action, created_at, updated_at) " +
                    "VALUES (?, ?, SYSTIMESTAMP, SYSTIMESTAMP)",
                tenantBytes, action,
            )
        }
    }

    // ------------------------------------------------------------
    // Login + cookie helpers (verbatim from TenantKpiIT)
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
}
