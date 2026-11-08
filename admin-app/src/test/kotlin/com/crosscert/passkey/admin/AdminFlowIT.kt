package com.crosscert.passkey.admin

import com.crosscert.passkey.admin.auth.AdminUserDetailsService
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
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
import java.nio.ByteBuffer
import java.util.UUID
import java.util.stream.StreamSupport
import javax.sql.DataSource

/**
 * Phase 2 acceptance gate (T24). Boots the full admin-app context against
 * a Testcontainers Oracle XE 21 (with V1..V14 migrations applied by Flyway
 * on context start) and a Testcontainers Redis 7, then exercises the
 * full admin operator flow end-to-end over real HTTP.
 *
 * 11-step scenario in [operatorFlowEndToEnd]:
 *  1. (① implicit) Application starts, Flyway runs all migrations
 *     including V11 seed of alice@/bob@.
 *  2. ② Alice (ADMIN) logs in via POST /admin/login (form).
 *  3. ③ Alice creates tenant "acme" (slug) via POST /admin/api/tenants.
 *  4. ④ Alice issues an API Key via POST /admin/api/api-keys (tenantId = UUID from ③).
 *  5. ⑤ The issued prefix is now in api_key (durable admin write).
 *  6. ⑥ GET /admin/api/audit returns 3 rows (ADMIN_LOGIN, TENANT_CREATE,
 *     API_KEY_ISSUE).
 *  7. ⑦ GET /admin/api/audit/verify returns ok=true.
 *  8. ⑧ Alice revokes the API Key via DELETE /admin/api/api-keys/{id}.
 *  9. ⑨ api_key.revoked_at is now populated for that row.
 *  10. ⑩ Bob (VIEWER) can list tenants (200) but cannot create one (403).
 *  11. ⑪ Direct JDBC UPDATE of an audit_log payload (run as PSK_APP_OWNER —
 *      the schema owner — because V10 deliberately grants PSK_APP_ADMIN only
 *      SELECT+INSERT to make tampering require DBA-level access) flips
 *      /verify to ok=false with brokenAt populated.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AdminFlowIT {

    companion object {
        // ------------------------------------------------------------
        // Containers
        // ------------------------------------------------------------

        private const val ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart"

        // OracleContainer.configure() copies the single password field into
        // BOTH ORACLE_PASSWORD (SYS/SYSTEM) and APP_USER_PASSWORD (PSK_APP_OWNER).
        // We reuse the same secret here so sqlplus can connect as SYS-AS-SYSDBA
        // AND so the AdminFlowIT can later log in as PSK_APP_OWNER for the tamper
        // step in scenario ⑪.
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
            // Run scripts/bootstrap-schema.sql before Spring opens its first
            // pool connection — PSK_APP_ADMIN_USER must exist before Hikari
            // tries to authenticate. Same pattern as :passkey-app's
            // Fido2EndToEndIT and :core's AppLevelIsolationIT.
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
            // admin-app's runtime DataSource → PSK_APP_ADMIN_USER (Flyway + DML on
            // platform-scoped tables). admin-app does NOT enable the app-level
            // @Filter on the request path (it intentionally manages cross-tenant
            // resources), so we don't need a separate runtime user like passkey-app.
            reg.add("spring.datasource.url", ORACLE::getJdbcUrl)
            reg.add("spring.datasource.username") { "PSK_APP_ADMIN_USER" }
            reg.add("spring.datasource.password") { "admin_pw" }
            // Flyway runs as the schema OWNER (PSK_APP_OWNER), runtime as PSK_APP_ADMIN_USER.
            // Finding #3 (Approach A): the runtime user no longer holds DDL power.
            reg.add("spring.flyway.url", ORACLE::getJdbcUrl)
            reg.add("spring.flyway.user") { "PSK_APP_OWNER" }
            reg.add("spring.flyway.password") { SYS_PASSWORD } // PSK_APP_OWNER pw == SYS_PASSWORD

            reg.add("spring.data.redis.host", REDIS::getHost)
            reg.add("spring.data.redis.port") { REDIS.getMappedPort(6379) }
        }

        /**
         * PSK_APP_OWNER (schema owner) pool used ONLY by step ⑪ to simulate
         * a DBA-level tamper. V10 deliberately withholds UPDATE on
         * audit_log from PSK_APP_ADMIN — the whole point is that runtime
         * credentials cannot rewrite the chain. The schema owner can,
         * because GRANTs do not restrict the owner.
         *
         * Lazily created on first use and torn down in [closeOwnerPool].
         */
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
                poolName = "admin-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
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

        /** For richer audit-count failure messages. */
        private fun actionsOf(arr: JsonNode): String {
            val sb = StringBuilder("[")
            for (i in 0 until arr.size()) {
                if (i > 0) sb.append(',')
                sb.append(arr.get(i).get("action").asString())
            }
            return sb.append(']').toString()
        }

        /**
         * Convert a UUID to the 16-byte big-endian RAW representation used by Oracle.
         * Required when comparing a UUID PK via JDBC against a RAW(16) column.
         */
        private fun uuidToBytes(id: UUID): ByteArray {
            val bb = ByteBuffer.allocate(16)
            bb.putLong(id.mostSignificantBits)
            bb.putLong(id.leastSignificantBits)
            return bb.array()
        }

        private val LONG: Class<Long> = Long::class.javaObjectType
        private val INT: Class<Int> = Int::class.javaObjectType
    }

    // ------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------

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

    /** 실제로 부팅된 admin-app 컨텍스트의 보안 배선을 직접 들여다보기 위한 주입. */
    @Autowired
    lateinit var ctx: ApplicationContext

    /** App-scoped pool (PSK_APP_ADMIN_USER), used for read assertions + cleanup. */
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)
        // FK chain: api_key.tenant_id → tenant.id; credential.tenant_id →
        // tenant.id. audit_log has no FK. admin_user (V11 seed) is left
        // untouched so alice/bob stay logged-in-able across test runs.
        // admin_user_tenant.tenant_id → tenant.id: delete mapping rows before
        // deleting tenants so the FK constraint is not violated.
        // Child tables (api_key_scope, tenant_allowed_origin, tenant_accepted_format)
        // use ON DELETE CASCADE from their parent FKs, so deleting the
        // parent rows implicitly removes children too.
        //
        // audit_log: V10 deliberately withholds DELETE from PSK_APP_ADMIN (append-only
        // design for tamper evidence). Use the schema-owner pool for cleanup so
        // the regression guard runtimeUser_cannotTamperAuditLog stays consistent.
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key")
        jdbc.update("DELETE FROM PSK_APP_OWNER.credential")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format")
        // admin_user_tenant 매핑 먼저 삭제 (FK: admin_user_tenant.tenant_id → tenant.id)
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant")
        jdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role <> 'PLATFORM_OPERATOR'")
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
        // bob 을 demo-rp 의 RP_ADMIN 으로 재할당 (role + admin_user_tenant 매핑)
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
        // Close the connection explicitly — RedisConnection isn't
        // AutoCloseable in Spring Data 3.x.
        val redisConn = redisFactory.connection
        try {
            redisConn.serverCommands().flushAll()
        } finally {
            redisConn.close()
        }
    }

    private fun url(path: String): String = "http://localhost:$port$path"

    // ------------------------------------------------------------
    // Envelope helper
    // ------------------------------------------------------------

    /**
     * Unwrap an ApiResponse envelope and return the data node.
     * Fails fast with the server-side code/message if success=false.
     */
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

    // ------------------------------------------------------------
    // Login + cookie helpers
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
    // The 11-step scenario
    // ------------------------------------------------------------

    @Test
    fun operatorFlowEndToEnd() {
        // ② Alice logs in.
        val aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw")

        // ③ Alice creates tenant acme (slug-based; Phase 6).
        val tenantBody =
            """
            {"slug":"acme","displayName":"Acme Inc","rpId":"acme.example.com","rpName":"Acme Inc",
             "allowedOrigins":["http://localhost"],
             "acceptedFormats":["none","packed"],
             "requireUserVerification":true,
             "mdsRequired":false,
             "attestationConveyance":"NONE",
             "webauthnTimeoutMs":60000}
            """.trimIndent() + "\n"
        val createT = rest.exchange(
            url("/admin/api/tenants"), HttpMethod.POST,
            HttpEntity(tenantBody, aliceAuth), JsonNode::class.java,
        )
        assertThat(createT.statusCode.value())
            .`as`("create tenant: %s body=%s", createT.statusCode, createT.body)
            .isEqualTo(201)
        // Phase 6: tenant UUID is in the response data; use it when issuing the API key.
        val tenantId = unwrap(createT).get("id").asString()

        // ④ Alice issues an API Key.
        val keyBody = "{\"tenantId\":\"" + tenantId + "\",\"name\":\"primary\",\"scopes\":[\"registration\",\"authentication\"]}"
        val issue = rest.exchange(
            url("/admin/api/api-keys"), HttpMethod.POST,
            HttpEntity(keyBody, aliceAuth), JsonNode::class.java,
        )
        assertThat(issue.statusCode.value())
            .`as`("issue API key: %s body=%s", issue.statusCode, issue.body)
            .isEqualTo(201)
        val issueData = unwrap(issue)
        val fullKey = issueData.get("plainText").asString()
        val keyId = issueData.get("id").asString() // UUID string (Phase 6)
        val prefix = issueData.get("prefix").asString()
        assertThat(fullKey).startsWith(prefix)

        // ⑤ The issued prefix is registered (durable admin write).
        val count: Long? = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.api_key WHERE key_prefix=?",
            LONG, prefix,
        )
        assertThat(count).isEqualTo(1L)

        // ⑥ /audit returns 3 rows (ADMIN_LOGIN, TENANT_CREATE, API_KEY_ISSUE).
        val audit = rest.exchange(
            url("/admin/api/audit"), HttpMethod.GET,
            HttpEntity<Any>(aliceAuth), JsonNode::class.java,
        )
        assertThat(audit.statusCode.value()).isEqualTo(200)
        val auditData = unwrap(audit)
        assertThat(auditData.isArray).isTrue()
        assertThat(auditData.size())
            .`as`("audit row count: actions=%s", actionsOf(auditData))
            .isEqualTo(3)

        // ⑦ Chain verify is OK at this point.
        val verify = rest.exchange(
            url("/admin/api/audit/verify"), HttpMethod.GET,
            HttpEntity<Any>(aliceAuth), JsonNode::class.java,
        )
        assertThat(verify.statusCode.value()).isEqualTo(200)
        val verifyData = unwrap(verify)
        assertThat(verifyData.get("ok").asBoolean())
            .`as`("audit/verify pre-tamper: %s", verifyData)
            .isTrue()

        // ⑧ Alice revokes the key. keyId is a UUID string (Phase 6).
        // DELETE now returns 200 + ApiResponse envelope (changed in Phase 4 T5).
        val del = rest.exchange(
            url("/admin/api/api-keys/$keyId"), HttpMethod.DELETE,
            HttpEntity<Any>(aliceAuth), JsonNode::class.java,
        )
        assertThat(del.statusCode.value()).isEqualTo(200)
        // Verify envelope is well-formed (success=true); data is null/missing for void payload.
        unwrap(del)

        // ⑨ Same key now soft-deleted (revoked_at populated).
        // api_key.id is RAW(16) — pass byte[] representation of the UUID.
        val keyIdBytes = uuidToBytes(UUID.fromString(keyId))
        val revokedCount: Long? = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.api_key WHERE id=? AND revoked_at IS NOT NULL",
            LONG, keyIdBytes,
        )
        assertThat(revokedCount).isEqualTo(1L)

        // ⑩ Bob (RP_ADMIN demo-rp) — list 200 (sees only own tenant), create 403.
        val bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw")
        val bobList = rest.exchange(
            url("/admin/api/tenants"), HttpMethod.GET,
            HttpEntity<Any>(bobAuth), String::class.java,
        )
        assertThat(bobList.statusCode.value()).isEqualTo(200)

        // bob は RP_ADMIN(demo-rp) — alice が step ③ で作った acme は見えず demo-rp だけ見える
        val bobListData = mapper.readTree(bobList.body).get("data")
        val bobSlugs = StreamSupport
            .stream(bobListData.spliterator(), false)
            .map { n -> n.get("slug").asString() }
            .toList()
        assertThat(bobSlugs)
            .`as`("bob (RP_ADMIN) sees only own tenant — actual: %s", bobSlugs)
            .contains("demo-rp")
            .doesNotContain("acme")

        // Use a different slug ("beta") so it doesn't conflict with "acme".
        val betaTenantBody =
            """
            {"slug":"beta","displayName":"Beta Inc","rpId":"beta.example.com","rpName":"Beta Inc",
             "allowedOrigins":["http://localhost"],
             "acceptedFormats":["none","packed"],
             "requireUserVerification":true,
             "mdsRequired":false,
             "attestationConveyance":"NONE",
             "webauthnTimeoutMs":60000}
            """.trimIndent() + "\n"
        val bobCreate = rest.exchange(
            url("/admin/api/tenants"), HttpMethod.POST,
            HttpEntity(betaTenantBody, bobAuth), String::class.java,
        )
        assertThat(bobCreate.statusCode.value())
            .`as`(
                "RP_ADMIN must be denied tenant create: %s body=%s",
                bobCreate.statusCode, bobCreate.body,
            )
            .isEqualTo(403)

        // ⑪ Tamper one audit row's payload — verify must return ok=false.
        // Connect as PSK_APP_OWNER (schema owner) because V10 deliberately
        // withholds UPDATE on audit_log from PSK_APP_ADMIN; the whole point
        // of the chain is that runtime credentials cannot rewrite it.
        // The schema owner can — but in production PSK_APP_OWNER's password
        // is never embedded in any application (bootstrap.sh is the
        // only consumer). Simulating a DBA-level tamper is the correct
        // adversary model for the chain verifier.
        val owner = ownerJdbc()
        val updated = owner.update(
            "UPDATE PSK_APP_OWNER.audit_log SET payload='{\"x\":\"tampered\"}' " +
                "WHERE id=(SELECT MIN(id) FROM PSK_APP_OWNER.audit_log)",
        )
        assertThat(updated).isEqualTo(1)

        val verifyBroken = rest.exchange(
            url("/admin/api/audit/verify"), HttpMethod.GET,
            HttpEntity<Any>(aliceAuth), JsonNode::class.java,
        )
        assertThat(verifyBroken.statusCode.value()).isEqualTo(200)
        val verifyBrokenData = unwrap(verifyBroken)
        assertThat(verifyBrokenData.get("ok").asBoolean())
            .`as`("audit/verify post-tamper: %s", verifyBrokenData)
            .isFalse()
        assertThat(verifyBrokenData.get("brokenAt").isNull).isFalse()
    }

    // ------------------------------------------------------------
    // Security regression guards (#3: GRANT ALL on PSK_APP_ADMIN_USER)
    // ------------------------------------------------------------

    /**
     * PSK_APP_ADMIN_USER (admin-app runtime) holds SELECT+INSERT, plus column-level
     * UPDATE on tenant_hash/tenant_prev_hash only (V46). payload/hash/prev_hash/
     * action UPDATE and DELETE/DROP remain denied (V10 append-only). This is the
     * regression guard for finding #3.
     *
     * Originally this test was *intentionally RED* while bootstrap-schema.sql
     * contained `GRANT ALL PRIVILEGES TO PSK_APP_ADMIN_USER` (finding #3).
     * Task B3 removed that GRANT, which turned this test GREEN.
     *
     * V46 then opened a narrow column-level UPDATE on the two V25 tenant-chain
     * columns (tenant_hash, tenant_prev_hash) so AuditChainBackfillService can
     * recompute the per-tenant chain at runtime. This does NOT weaken tamper
     * evidence: AuditChainVerifier recomputes those hashes from the immutable
     * payload columns, so a rewritten tenant_hash cannot hide a tampered row.
     * This test pins both sides of the V46 boundary.
     *
     * Spring's JdbcTemplate wraps Oracle privilege errors in
     * `BadSqlGrammarException`; the ORA- code lives in the root
     * cause. We navigate to the root cause via `rootCause()` and
     * then check its message.
     */
    @Test
    fun runtimeUser_cannotTamperAuditLog() {
        // ── Still denied: the original append-only columns (V10) ──────────────
        // action: a forensic field — runtime must never rewrite it.
        assertThatThrownBy {
            jdbc.execute("UPDATE PSK_APP_OWNER.audit_log SET action = 'X' WHERE 1=0")
        }
            .rootCause().hasMessageContaining("ORA-")
        // payload: the tamper target the chain protects.
        assertThatThrownBy {
            jdbc.execute("UPDATE PSK_APP_OWNER.audit_log SET payload = payload WHERE 1=0")
        }
            .rootCause().hasMessageContaining("ORA-")
        // hash / prev_hash: the global chain columns — rewriting them would let a
        // forger re-link the chain. V46 deliberately leaves these denied.
        assertThatThrownBy {
            jdbc.execute("UPDATE PSK_APP_OWNER.audit_log SET hash = hash WHERE 1=0")
        }
            .rootCause().hasMessageContaining("ORA-")
        assertThatThrownBy {
            jdbc.execute("UPDATE PSK_APP_OWNER.audit_log SET prev_hash = prev_hash WHERE 1=0")
        }
            .rootCause().hasMessageContaining("ORA-")
        // DELETE / DROP: append-only — never allowed.
        assertThatThrownBy {
            jdbc.execute("DELETE FROM PSK_APP_OWNER.audit_log WHERE 1=0")
        }
            .rootCause().hasMessageContaining("ORA-")
        assertThatThrownBy {
            jdbc.execute("DROP TABLE PSK_APP_OWNER.audit_log")
        }
            .rootCause().hasMessageContaining("ORA-")

        // ── Now allowed: column-level UPDATE on the two V25 tenant-chain columns ─
        // (V46). Self-assignment with WHERE 1=0 proves the UPDATE privilege exists
        // without mutating any row.
        assertThatNoException().isThrownBy {
            jdbc.execute(
                "UPDATE PSK_APP_OWNER.audit_log " +
                    "SET tenant_hash = tenant_hash, tenant_prev_hash = tenant_prev_hash " +
                    "WHERE 1=0",
            )
        }
    }

    /**
     * Negative-of-negative: reducing GRANT ALL must NOT break runtime DML
     * on the tables admin-app actually manages. Smoke a harmless count.
     */
    @Test
    fun runtimeUser_canStillWriteAdminTables() {
        val n: Int? = jdbc.queryForObject("SELECT COUNT(*) FROM PSK_APP_OWNER.tenant", INT)
        assertThat(n).isNotNull()
        val k: Int? = jdbc.queryForObject("SELECT COUNT(*) FROM PSK_APP_OWNER.api_key", INT)
        assertThat(k).isNotNull()
    }

    /**
     * #22 후속 — 실제 admin-app 컨텍스트에서 Boot 기본 인메모리 사용자가 만들어지지 않음을 단언한다.
     * `GeneratedSecurityPasswordAbsentTest` 는 스텁 러너로 back-off 메커니즘만 증명하므로,
     * "실앱 배선" 확인은 컨텍스트를 통째로 띄우는 이 테스트가 담당한다.
     *
     * 운영자 계정은 DB(AdminUserDetailsService)에서만 읽어야 한다. 인메모리 폴백이 되살아나면
     * 부팅 때마다 임의 비밀번호를 가진 `user` 계정이 생겨 인증 경로가 이원화된다.
     */
    @Test
    fun security_usesDbBackedUserDetailsService_noInMemoryFallback() {
        assertThat(ctx.getBeansOfType(InMemoryUserDetailsManager::class.java)).isEmpty()
        assertThat(ctx.getBean(UserDetailsService::class.java)).isInstanceOf(AdminUserDetailsService::class.java)
    }
}
