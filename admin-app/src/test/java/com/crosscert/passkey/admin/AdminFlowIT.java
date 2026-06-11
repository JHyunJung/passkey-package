package com.crosscert.passkey.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 acceptance gate (T24). Boots the full admin-app context against
 * a Testcontainers Oracle XE 21 (with V1..V14 migrations applied by Flyway
 * on context start) and a Testcontainers Redis 7, then exercises the
 * full admin operator flow end-to-end over real HTTP.
 *
 * <p>11-step scenario in {@link #operatorFlowEndToEnd()}:
 * <ol>
 *   <li>(① implicit) Application starts, Flyway runs all migrations
 *       including V11 seed of alice@/bob@.</li>
 *   <li>② Alice (ADMIN) logs in via POST /admin/login (form).</li>
 *   <li>③ Alice creates tenant "acme" (slug) via POST /admin/api/tenants.</li>
 *   <li>④ Alice issues an API Key via POST /admin/api/api-keys (tenantId = UUID from ③).</li>
 *   <li>⑤ The issued prefix is now in api_key (durable admin write).</li>
 *   <li>⑥ GET /admin/api/audit returns 3 rows (ADMIN_LOGIN, TENANT_CREATE,
 *       API_KEY_ISSUE).</li>
 *   <li>⑦ GET /admin/api/audit/verify returns ok=true.</li>
 *   <li>⑧ Alice revokes the API Key via DELETE /admin/api/api-keys/{id}.</li>
 *   <li>⑨ api_key.revoked_at is now populated for that row.</li>
 *   <li>⑩ Bob (VIEWER) can list tenants (200) but cannot create one (403).</li>
 *   <li>⑪ Direct JDBC UPDATE of an audit_log payload (run as APP_OWNER —
 *       the schema owner — because V10 deliberately grants APP_ADMIN only
 *       SELECT+INSERT to make tampering require DBA-level access) flips
 *       /verify to ok=false with brokenAt populated.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AdminFlowIT {

    // ------------------------------------------------------------
    // Containers
    // ------------------------------------------------------------

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    // OracleContainer.configure() copies the single password field into
    // BOTH ORACLE_PASSWORD (SYS/SYSTEM) and APP_USER_PASSWORD (APP_OWNER).
    // We reuse the same secret here so sqlplus can connect as SYS-AS-SYSDBA
    // AND so the AdminFlowIT can later log in as APP_OWNER for the tamper
    // step in scenario ⑪.
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        // Run scripts/bootstrap-vpd.sql before Spring opens its first
        // pool connection — APP_ADMIN_USER must exist before Hikari
        // tries to authenticate. Same pattern as :passkey-app's
        // Fido2EndToEndIT and :core's VpdIsolationIT.
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-vpd.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-vpd.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        // admin-app's runtime DataSource → APP_ADMIN_USER (Flyway + DML on
        // platform-scoped tables). admin-app does NOT exercise VPD on the
        // request path (it intentionally manages cross-tenant resources),
        // so we don't need a separate runtime user like passkey-app does.
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        // Flyway runs as the schema OWNER (APP_OWNER), runtime as APP_ADMIN_USER.
        // Finding #3 (Approach A): the runtime user no longer holds DDL power.
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD); // APP_OWNER pw == SYS_PASSWORD

        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // ------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    /** App-scoped pool (APP_ADMIN_USER), used for read assertions + cleanup. */
    JdbcTemplate jdbc;

    /**
     * APP_OWNER (schema owner) pool used ONLY by step ⑪ to simulate
     * a DBA-level tamper. V10 deliberately withholds UPDATE on
     * audit_log from APP_ADMIN — the whole point is that runtime
     * credentials cannot rewrite the chain. The schema owner can,
     * because GRANTs do not restrict the owner.
     *
     * <p>Lazily created on first use and torn down in {@link #closeOwnerPool()}.
     */
    private static HikariDataSource ownerPool;

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        // FK chain: api_key.tenant_id → tenant.id; credential.tenant_id →
        // tenant.id. audit_log has no FK. admin_user (V11 seed) is left
        // untouched so alice/bob stay logged-in-able across test runs.
        // V23 added admin_user.tenant_id FK → tenant.id: null it out before
        // deleting tenants so the FK constraint is not violated.
        // Child tables (api_key_scope, tenant_allowed_origin, tenant_accepted_format)
        // use ON DELETE CASCADE from their parent FKs, so deleting the
        // parent rows implicitly removes children too.
        //
        // audit_log: V10 deliberately withholds DELETE from APP_ADMIN (append-only
        // design for tamper evidence). Use the schema-owner pool for cleanup so
        // the regression guard runtimeUser_cannotTamperAuditLog stays consistent.
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        // NULL out admin_user.tenant_id before deleting tenants (V23 FK —
        // fk_admin_user_tenant blocks DELETE FROM tenant while child rows exist)
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
        // Re-seed demo-rp tenant (seeded by V23; deleted above for clean slate).
        // bob (RP_ADMIN) needs this tenant to exist and be assigned to him.
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                    require_user_verification, mds_required, created_at, updated_at)
                VALUES (HEXTORAW('0000000000000000000000000000C0DE'),
                    'demo-rp', 'Demo RP', 'localhost', 'Demo RP', 'active', 'Y', 'N',
                    SYSTIMESTAMP, SYSTIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
                VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'), 'http://localhost:9090', 0)
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_accepted_format (id, tenant_id, format)
                VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'), 'none')
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_accepted_format (id, tenant_id, format)
                VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'), 'packed')
                """);
        // Re-assign bob to demo-rp as RP_ADMIN (mirrors V23 step 9)
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'RP_ADMIN',
                       tenant_id = HEXTORAW('0000000000000000000000000000C0DE')
                 WHERE email = 'bob@crosscert.com'
                """);
        // Close the connection explicitly — RedisConnection isn't
        // AutoCloseable in Spring Data 3.x.
        var redisConn = redisFactory.getConnection();
        try {
            redisConn.serverCommands().flushAll();
        } finally {
            redisConn.close();
        }
    }

    @AfterAll
    static void closeOwnerPool() {
        if (ownerPool != null) {
            ownerPool.close();
            ownerPool = null;
        }
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    // ------------------------------------------------------------
    // Envelope helper
    // ------------------------------------------------------------

    /**
     * Unwrap an ApiResponse envelope and return the data node.
     * Fails fast with the server-side code/message if success=false.
     */
    private JsonNode unwrap(ResponseEntity<JsonNode> res) {
        JsonNode env = res.getBody();
        if (env == null) {
            throw new AssertionError("API call returned empty body (status=" + res.getStatusCode() + ")");
        }
        if (!env.path("success").asBoolean()) {
            throw new AssertionError("API call failed (status=" + res.getStatusCode() +
                                     ", code=" + env.path("code").asText() +
                                     ", message=" + env.path("message").asText() + ")");
        }
        return env.path("data");
    }

    private static synchronized JdbcTemplate ownerJdbc() {
        if (ownerPool == null) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(ORACLE.getJdbcUrl());
            ds.setUsername("APP_OWNER");
            ds.setPassword(SYS_PASSWORD);
            ds.setMaximumPoolSize(2);
            ds.setPoolName("admin-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    // ------------------------------------------------------------
    // Login + cookie helpers
    // ------------------------------------------------------------

    /**
     * Performs Spring Security form login and returns an HttpHeaders
     * pre-populated with the session cookie + CSRF token, suitable for
     * subsequent JSON API calls.
     *
     * <p>Flow:
     * <ol>
     *   <li>Seed: GET /admin/api/me to acquire XSRF-TOKEN cookie.
     *       AdminSecurityConfig sets {@code setCsrfRequestAttributeName(null)}
     *       so the CSRF cookie is emitted on every response — including
     *       the 401 we get here (no auth).</li>
     *   <li>POST /admin/login as form data, sending the XSRF cookie back
     *       in both Cookie header (CookieCsrfTokenRepository validates
     *       cookie==header) and X-XSRF-TOKEN header.</li>
     *   <li>Replace cookies by name (not append) so that a rotated
     *       XSRF-TOKEN on login does not leave two same-named cookies
     *       in the outgoing Cookie header — servers don't all agree on
     *       which one "wins", and CookieCsrfTokenRepository would reject
     *       a mismatch between Cookie and X-XSRF-TOKEN header.</li>
     *   <li>Post-login "warm-up" GET /admin/api/me with the session
     *       cookie. This forces the server to emit a fresh CSRF cookie
     *       for the authenticated session (in case Spring's
     *       CsrfAuthenticationStrategy did not rotate during the
     *       login POST), guaranteeing the returned headers are ready
     *       for state-changing JSON calls.</li>
     * </ol>
     */
    private HttpHeaders loginAs(String email, String password) {
        Map<String, String> jar = new LinkedHashMap<>();

        // 1. Seed CSRF cookie via unauthenticated GET.
        ResponseEntity<String> seed = rest.exchange(
                url("/admin/api/me"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        mergeSetCookiesByName(jar, seed.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertThat(jar.get("XSRF-TOKEN"))
                .as("CSRF cookie should be emitted on seed request (even 401)")
                .isNotNull();

        // 2. POST form login.
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        loginHeaders.set(HttpHeaders.COOKIE, renderCookieHeader(jar));
        loginHeaders.set("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        String body = "email=" + email + "&password=" + password;
        ResponseEntity<String> login = rest.exchange(
                url("/admin/login"), HttpMethod.POST,
                new HttpEntity<>(body, loginHeaders), String.class);
        assertThat(login.getStatusCode().is2xxSuccessful())
                .as("login as %s: %s body=%s", email, login.getStatusCode(), login.getBody())
                .isTrue();
        mergeSetCookiesByName(jar, login.getHeaders().get(HttpHeaders.SET_COOKIE));

        // 3. Warm-up GET /me with the authenticated session cookie.
        //    Spring's default CsrfAuthenticationStrategy may rotate the
        //    XSRF token at login, but in any case this round-trip
        //    guarantees the cookie + header pair we return matches the
        //    server's authoritative session token.
        HttpHeaders warmHeaders = new HttpHeaders();
        warmHeaders.set(HttpHeaders.COOKIE, renderCookieHeader(jar));
        ResponseEntity<String> warm = rest.exchange(
                url("/admin/api/me"), HttpMethod.GET,
                new HttpEntity<>(warmHeaders), String.class);
        mergeSetCookiesByName(jar, warm.getHeaders().get(HttpHeaders.SET_COOKIE));

        HttpHeaders auth = new HttpHeaders();
        auth.setContentType(MediaType.APPLICATION_JSON);
        auth.set(HttpHeaders.COOKIE, renderCookieHeader(jar));
        auth.set("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        return auth;
    }

    /**
     * Parse each Set-Cookie header's name=value pair and upsert it into
     * the cookie jar. Later Set-Cookie values for the same cookie name
     * replace earlier ones — matching browser semantics and avoiding
     * the "two XSRF-TOKEN cookies in the same Cookie header" hazard
     * that codex P1 flagged.
     */
    private static void mergeSetCookiesByName(Map<String, String> jar, List<String> setCookies) {
        if (setCookies == null) return;
        for (String sc : setCookies) {
            String pair = sc.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1);
            if (name.isEmpty()) continue;
            // Empty value = server-sent cookie delete. Remove from jar.
            if (value.isEmpty()) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
    }

    private static String renderCookieHeader(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------
    // The 11-step scenario
    // ------------------------------------------------------------

    @Test
    void operatorFlowEndToEnd() throws Exception {
        // ② Alice logs in.
        HttpHeaders aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // ③ Alice creates tenant acme (slug-based; Phase 6).
        String tenantBody = """
                {"slug":"acme","displayName":"Acme Inc","rpId":"acme.example.com","rpName":"Acme Inc",
                 "allowedOrigins":["http://localhost"],
                 "acceptedFormats":["none","packed"],
                 "requireUserVerification":true,
                 "mdsRequired":false,
                 "attestationConveyance":"NONE",
                 "webauthnTimeoutMs":60000}
                """;
        ResponseEntity<JsonNode> createT = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(tenantBody, aliceAuth), JsonNode.class);
        assertThat(createT.getStatusCode().value())
                .as("create tenant: %s body=%s", createT.getStatusCode(), createT.getBody())
                .isEqualTo(201);
        // Phase 6: tenant UUID is in the response data; use it when issuing the API key.
        String tenantId = unwrap(createT).get("id").asText();

        // ④ Alice issues an API Key.
        String keyBody = "{\"tenantId\":\"" + tenantId + "\",\"name\":\"primary\",\"scopes\":[\"registration\",\"authentication\"]}";
        ResponseEntity<JsonNode> issue = rest.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(keyBody, aliceAuth), JsonNode.class);
        assertThat(issue.getStatusCode().value())
                .as("issue API key: %s body=%s", issue.getStatusCode(), issue.getBody())
                .isEqualTo(201);
        JsonNode issueData = unwrap(issue);
        String fullKey = issueData.get("plainText").asText();
        String keyId = issueData.get("id").asText();   // UUID string (Phase 6)
        String prefix = issueData.get("prefix").asText();
        assertThat(fullKey).startsWith(prefix);

        // ⑤ The issued prefix is registered (durable admin write).
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.api_key WHERE key_prefix=?",
                Long.class, prefix);
        assertThat(count).isEqualTo(1L);

        // ⑥ /audit returns 3 rows (ADMIN_LOGIN, TENANT_CREATE, API_KEY_ISSUE).
        ResponseEntity<JsonNode> audit = rest.exchange(
                url("/admin/api/audit"), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(audit.getStatusCode().value()).isEqualTo(200);
        JsonNode auditData = unwrap(audit);
        assertThat(auditData.isArray()).isTrue();
        assertThat(auditData.size())
                .as("audit row count: actions=%s", actionsOf(auditData))
                .isEqualTo(3);

        // ⑦ Chain verify is OK at this point.
        ResponseEntity<JsonNode> verify = rest.exchange(
                url("/admin/api/audit/verify"), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(verify.getStatusCode().value()).isEqualTo(200);
        JsonNode verifyData = unwrap(verify);
        assertThat(verifyData.get("ok").asBoolean())
                .as("audit/verify pre-tamper: %s", verifyData)
                .isTrue();

        // ⑧ Alice revokes the key. keyId is a UUID string (Phase 6).
        // DELETE now returns 200 + ApiResponse envelope (changed in Phase 4 T5).
        ResponseEntity<JsonNode> del = rest.exchange(
                url("/admin/api/api-keys/" + keyId), HttpMethod.DELETE,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(del.getStatusCode().value()).isEqualTo(200);
        // Verify envelope is well-formed (success=true); data is null/missing for void payload.
        unwrap(del);

        // ⑨ Same key now soft-deleted (revoked_at populated).
        // api_key.id is RAW(16) — pass byte[] representation of the UUID.
        byte[] keyIdBytes = uuidToBytes(UUID.fromString(keyId));
        Long revokedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.api_key WHERE id=? AND revoked_at IS NOT NULL",
                Long.class, keyIdBytes);
        assertThat(revokedCount).isEqualTo(1L);

        // ⑩ Bob (RP_ADMIN demo-rp) — list 200 (sees only own tenant), create 403.
        HttpHeaders bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw");
        ResponseEntity<String> bobList = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.GET,
                new HttpEntity<>(bobAuth), String.class);
        assertThat(bobList.getStatusCode().value()).isEqualTo(200);

        // bob は RP_ADMIN(demo-rp) — alice が step ③ で作った acme は見えず demo-rp だけ見える
        com.fasterxml.jackson.databind.JsonNode bobListData = mapper.readTree(bobList.getBody()).get("data");
        java.util.List<String> bobSlugs = java.util.stream.StreamSupport
                .stream(bobListData.spliterator(), false)
                .map(n -> n.get("slug").asText())
                .toList();
        assertThat(bobSlugs)
                .as("bob (RP_ADMIN) sees only own tenant — actual: %s", bobSlugs)
                .contains("demo-rp")
                .doesNotContain("acme");

        // Use a different slug ("beta") so it doesn't conflict with "acme".
        String betaTenantBody = """
                {"slug":"beta","displayName":"Beta Inc","rpId":"beta.example.com","rpName":"Beta Inc",
                 "allowedOrigins":["http://localhost"],
                 "acceptedFormats":["none","packed"],
                 "requireUserVerification":true,
                 "mdsRequired":false,
                 "attestationConveyance":"NONE",
                 "webauthnTimeoutMs":60000}
                """;
        ResponseEntity<String> bobCreate = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(betaTenantBody, bobAuth), String.class);
        assertThat(bobCreate.getStatusCode().value())
                .as("RP_ADMIN must be denied tenant create: %s body=%s",
                        bobCreate.getStatusCode(), bobCreate.getBody())
                .isEqualTo(403);

        // ⑪ Tamper one audit row's payload — verify must return ok=false.
        // Connect as APP_OWNER (schema owner) because V10 deliberately
        // withholds UPDATE on audit_log from APP_ADMIN; the whole point
        // of the chain is that runtime credentials cannot rewrite it.
        // The schema owner can — but in production APP_OWNER's password
        // is never embedded in any application (bootstrap.sh is the
        // only consumer). Simulating a DBA-level tamper is the correct
        // adversary model for the chain verifier.
        JdbcTemplate owner = ownerJdbc();
        int updated = owner.update(
                "UPDATE APP_OWNER.audit_log SET payload='{\"x\":\"tampered\"}' " +
                "WHERE id=(SELECT MIN(id) FROM APP_OWNER.audit_log)");
        assertThat(updated).isEqualTo(1);

        ResponseEntity<JsonNode> verifyBroken = rest.exchange(
                url("/admin/api/audit/verify"), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(verifyBroken.getStatusCode().value()).isEqualTo(200);
        JsonNode verifyBrokenData = unwrap(verifyBroken);
        assertThat(verifyBrokenData.get("ok").asBoolean())
                .as("audit/verify post-tamper: %s", verifyBrokenData)
                .isFalse();
        assertThat(verifyBrokenData.get("brokenAt").isNull()).isFalse();
    }

    // ------------------------------------------------------------
    // Security regression guards (#3: GRANT ALL on APP_ADMIN_USER)
    // ------------------------------------------------------------

    /**
     * APP_ADMIN_USER (admin-app runtime) holds SELECT+INSERT, plus column-level
     * UPDATE on tenant_hash/tenant_prev_hash only (V46). payload/hash/prev_hash/
     * action UPDATE and DELETE/DROP remain denied (V10 append-only). This is the
     * regression guard for finding #3.
     *
     * <p>Originally this test was <em>intentionally RED</em> while bootstrap-vpd.sql
     * contained {@code GRANT ALL PRIVILEGES TO APP_ADMIN_USER} (finding #3).
     * Task B3 removed that GRANT, which turned this test GREEN.
     *
     * <p>V46 then opened a narrow column-level UPDATE on the two V25 tenant-chain
     * columns (tenant_hash, tenant_prev_hash) so AuditChainBackfillService can
     * recompute the per-tenant chain at runtime. This does NOT weaken tamper
     * evidence: AuditChainVerifier recomputes those hashes from the immutable
     * payload columns, so a rewritten tenant_hash cannot hide a tampered row.
     * This test pins both sides of the V46 boundary.
     *
     * <p>Spring's JdbcTemplate wraps Oracle privilege errors in
     * {@code BadSqlGrammarException}; the ORA- code lives in the root
     * cause. We navigate to the root cause via {@code rootCause()} and
     * then check its message.
     */
    @Test
    void runtimeUser_cannotTamperAuditLog() {
        // ── Still denied: the original append-only columns (V10) ──────────────
        // action: a forensic field — runtime must never rewrite it.
        assertThatThrownBy(() ->
                jdbc.execute("UPDATE APP_OWNER.audit_log SET action = 'X' WHERE 1=0"))
            .rootCause().hasMessageContaining("ORA-");
        // payload: the tamper target the chain protects.
        assertThatThrownBy(() ->
                jdbc.execute("UPDATE APP_OWNER.audit_log SET payload = payload WHERE 1=0"))
            .rootCause().hasMessageContaining("ORA-");
        // hash / prev_hash: the global chain columns — rewriting them would let a
        // forger re-link the chain. V46 deliberately leaves these denied.
        assertThatThrownBy(() ->
                jdbc.execute("UPDATE APP_OWNER.audit_log SET hash = hash WHERE 1=0"))
            .rootCause().hasMessageContaining("ORA-");
        assertThatThrownBy(() ->
                jdbc.execute("UPDATE APP_OWNER.audit_log SET prev_hash = prev_hash WHERE 1=0"))
            .rootCause().hasMessageContaining("ORA-");
        // DELETE / DROP: append-only — never allowed.
        assertThatThrownBy(() ->
                jdbc.execute("DELETE FROM APP_OWNER.audit_log WHERE 1=0"))
            .rootCause().hasMessageContaining("ORA-");
        assertThatThrownBy(() ->
                jdbc.execute("DROP TABLE APP_OWNER.audit_log"))
            .rootCause().hasMessageContaining("ORA-");

        // ── Now allowed: column-level UPDATE on the two V25 tenant-chain columns ─
        // (V46). Self-assignment with WHERE 1=0 proves the UPDATE privilege exists
        // without mutating any row.
        assertThatNoException().isThrownBy(() ->
                jdbc.execute("UPDATE APP_OWNER.audit_log "
                        + "SET tenant_hash = tenant_hash, tenant_prev_hash = tenant_prev_hash "
                        + "WHERE 1=0"));
    }

    /**
     * Negative-of-negative: reducing GRANT ALL must NOT break runtime DML
     * on the tables admin-app actually manages. Smoke a harmless count.
     */
    @Test
    void runtimeUser_canStillWriteAdminTables() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM APP_OWNER.tenant", Integer.class);
        assertThat(n).isNotNull();
        Integer k = jdbc.queryForObject("SELECT COUNT(*) FROM APP_OWNER.api_key", Integer.class);
        assertThat(k).isNotNull();
    }

    /** For richer audit-count failure messages. */
    private static String actionsOf(JsonNode arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(arr.get(i).get("action").asText());
        }
        return sb.append(']').toString();
    }

    /**
     * Convert a UUID to the 16-byte big-endian RAW representation used by Oracle.
     * Required when comparing a UUID PK via JDBC against a RAW(16) column.
     */
    private static byte[] uuidToBytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
