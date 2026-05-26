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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
 *   <li>③ Alice creates tenant T_A via POST /admin/api/tenants.</li>
 *   <li>④ Alice issues an API Key via POST /admin/api/api-keys.</li>
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
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
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

        // ③ Alice creates tenant T_A.
        String tenantBody = """
                {"id":"T_A","displayName":"Tenant A","rpId":"localhost","rpName":"Tenant A",
                 "allowedOriginsJson":"[\\"http://localhost\\"]",
                 "attestationPolicyJson":"{\\"acceptedFormats\\":[\\"none\\"],\\"requireUserVerification\\":true,\\"mdsRequired\\":false}"}
                """;
        ResponseEntity<JsonNode> createT = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(tenantBody, aliceAuth), JsonNode.class);
        assertThat(createT.getStatusCode().value())
                .as("create tenant: %s body=%s", createT.getStatusCode(), createT.getBody())
                .isEqualTo(201);

        // ④ Alice issues an API Key.
        String keyBody = """
                {"tenantId":"T_A","name":"primary","scopesJson":"[]"}
                """;
        ResponseEntity<JsonNode> issue = rest.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(keyBody, aliceAuth), JsonNode.class);
        assertThat(issue.getStatusCode().value())
                .as("issue API key: %s body=%s", issue.getStatusCode(), issue.getBody())
                .isEqualTo(201);
        String fullKey = issue.getBody().get("plainText").asText();
        long keyId = issue.getBody().get("id").asLong();
        String prefix = issue.getBody().get("prefix").asText();
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
        assertThat(audit.getBody().isArray()).isTrue();
        assertThat(audit.getBody().size())
                .as("audit row count: actions=%s", actionsOf(audit.getBody()))
                .isEqualTo(3);

        // ⑦ Chain verify is OK at this point.
        ResponseEntity<JsonNode> verify = rest.exchange(
                url("/admin/api/audit/verify"), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(verify.getStatusCode().value()).isEqualTo(200);
        assertThat(verify.getBody().get("ok").asBoolean())
                .as("audit/verify pre-tamper: %s", verify.getBody())
                .isTrue();

        // ⑧ Alice revokes the key.
        ResponseEntity<String> del = rest.exchange(
                url("/admin/api/api-keys/" + keyId), HttpMethod.DELETE,
                new HttpEntity<>(aliceAuth), String.class);
        assertThat(del.getStatusCode().value()).isEqualTo(204);

        // ⑨ Same key now soft-deleted (revoked_at populated).
        Long revokedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.api_key WHERE id=? AND revoked_at IS NOT NULL",
                Long.class, keyId);
        assertThat(revokedCount).isEqualTo(1L);

        // ⑩ Bob (VIEWER) — list 200, create 403.
        HttpHeaders bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw");
        ResponseEntity<String> bobList = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.GET,
                new HttpEntity<>(bobAuth), String.class);
        assertThat(bobList.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> bobCreate = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(tenantBody.replace("T_A", "T_B"), bobAuth), String.class);
        assertThat(bobCreate.getStatusCode().value())
                .as("VIEWER must be denied tenant create: %s body=%s",
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
        assertThat(verifyBroken.getBody().get("ok").asBoolean())
                .as("audit/verify post-tamper: %s", verifyBroken.getBody())
                .isFalse();
        assertThat(verifyBroken.getBody().get("brokenAt").isNull()).isFalse();
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
}
