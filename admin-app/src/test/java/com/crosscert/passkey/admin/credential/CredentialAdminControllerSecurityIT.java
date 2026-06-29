package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
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
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class CredentialAdminControllerSecurityIT {

    // ----------------------------------------------------------------
    // Containers (same config as AdminFlowIT and T6 TenantAdminControllerUpdateIT)
    // ----------------------------------------------------------------

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-schema.sql"),
                    "/tmp/bootstrap-schema.sql");

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-schema.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-schema.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper om;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;
    @Autowired CredentialRepository credentialRepository;

    JdbcTemplate jdbc;
    private final SecureRandom rng = new SecureRandom();
    // Separate plain RestTemplate (not TestRestTemplate) for error response inspection
    private final RestTemplate http = new RestTemplate();

    /** APP_OWNER (schema owner) pool — used only for owner-only table cleanup in resetState(). */
    private static HikariDataSource ownerPool;

    @AfterAll
    static void closeOwnerPool() {
        if (ownerPool != null) {
            ownerPool.close();
            ownerPool = null;
        }
    }

    private static synchronized JdbcTemplate ownerJdbc() {
        if (ownerPool == null) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(ORACLE.getJdbcUrl());
            ds.setUsername("APP_OWNER");
            ds.setPassword(SYS_PASSWORD);
            ds.setMaximumPoolSize(2);
            ds.setPoolName("credential-security-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

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
        // audit_log: APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        // Clear admin_user_tenant mapping before deleting tenants (FK admin_user_tenant.tenant_id → tenant)
        jdbc.update("DELETE FROM APP_OWNER.admin_user_tenant");
        jdbc.update("UPDATE APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'");
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
        // Re-assign bob to demo-rp as RP_ADMIN (role + admin_user_tenant 매핑; N:M 전환).
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'RP_ADMIN'
                 WHERE email = 'bob@crosscert.com'
                """);
        jdbc.update("""
                MERGE INTO APP_OWNER.admin_user_tenant t
                USING (SELECT HEXTORAW('00000000000000000000000000000011') AS aid,
                              HEXTORAW('0000000000000000000000000000C0DE') AS tid FROM dual) s
                   ON (t.admin_user_id = s.aid AND t.tenant_id = s.tid)
                 WHEN NOT MATCHED THEN
                   INSERT (admin_user_id, tenant_id, created_at, created_by)
                   VALUES (s.aid, s.tid, SYSTIMESTAMP, 'it')
                """);
        var redisConn = redisFactory.getConnection();
        try {
            redisConn.serverCommands().flushAll();
        } finally {
            redisConn.close();
        }
    }

    // ----------------------------------------------------------------
    // Login helper (inlined from AdminFlowIT.loginAs / T6 TenantAdminControllerUpdateIT)
    // ----------------------------------------------------------------

    private String url(String path) { return "http://localhost:" + port + path; }

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

        // 3. Warm-up GET /me — guarantees fresh CSRF token for authenticated session.
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

    private static void mergeSetCookiesByName(Map<String, String> jar, List<String> setCookies) {
        if (setCookies == null) return;
        for (String sc : setCookies) {
            String pair = sc.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1);
            if (name.isEmpty()) continue;
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

    /** Unwrap ApiResponse envelope — fail fast on success=false. */
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

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String createTenantViaApi(HttpHeaders auth, String slug, String displayName) throws Exception {
        Map<String, Object> reqBody = Map.of(
                "slug", slug,
                "displayName", displayName,
                "rpId", "localhost",
                "rpName", "RP for " + slug,
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false,
                "attestationConveyance", "NONE",
                "webauthnTimeoutMs", 60000);
        ResponseEntity<JsonNode> res = rest.exchange(
                url("/admin/api/tenants"),
                HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(reqBody), auth),
                JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful())
                .as("create tenant slug=%s: %s body=%s", slug, res.getStatusCode(), res.getBody())
                .isTrue();
        return unwrap(res).get("id").asText();
    }

    /**
     * Directly inserts a credential fixture into tenant_A via CredentialRepository.
     * Uses the Credential constructor (no individual setters exist).
     * Returns the credentialId as base64url (no padding) — the format used by admin endpoints.
     */
    private String insertFixtureCredential(UUID tenantId) {
        byte[] credId = new byte[16];
        rng.nextBytes(credId);
        byte[] userHandle = new byte[32];
        rng.nextBytes(userHandle);
        byte[] publicKey = new byte[]{ 0x01, 0x02, 0x03 };  // dummy; admin endpoint does not validate CBOR
        byte[] aaguid = new byte[16];                        // zero aaguid

        Credential c = new Credential(tenantId, userHandle, credId, publicKey, aaguid);
        credentialRepository.save(c);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(credId);
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
    void crossTenantRevoke_isRejected_andCredentialIsListedOnlyInOwnTenant() throws Exception {
        // Build auth headers using the same inline login pattern as T6
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // 1. Create two tenants
        String tenantAId = createTenantViaApi(auth, "sec-it-a-" + UUID.randomUUID().toString().substring(0, 8), "Tenant A");
        String tenantBId = createTenantViaApi(auth, "sec-it-b-" + UUID.randomUUID().toString().substring(0, 8), "Tenant B");

        // 2. Insert credential fixture directly into tenant_A (bypasses ceremony)
        String credentialIdB64 = insertFixtureCredential(UUID.fromString(tenantAId));

        // 3. GET tenant_A credentials → credential MUST be present
        ResponseEntity<JsonNode> listA = rest.exchange(
                url("/admin/api/tenants/" + tenantAId + "/credentials"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        assertThat(listA.getStatusCode().is2xxSuccessful())
                .as("GET tenant_A credentials: %s", listA.getStatusCode())
                .isTrue();
        boolean foundInA = StreamSupport.stream(
                        unwrap(listA).get("content").spliterator(), false)
                .anyMatch(n -> credentialIdB64.equals(n.get("credentialId").asText()));
        assertThat(foundInA)
                .as("credential must appear in tenant_A list")
                .isTrue();

        // 4. GET tenant_B credentials → credential must NOT appear
        ResponseEntity<JsonNode> listB = rest.exchange(
                url("/admin/api/tenants/" + tenantBId + "/credentials"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        assertThat(listB.getStatusCode().is2xxSuccessful()).isTrue();
        boolean foundInB = StreamSupport.stream(
                        unwrap(listB).get("content").spliterator(), false)
                .anyMatch(n -> credentialIdB64.equals(n.get("credentialId").asText()));
        assertThat(foundInB)
                .as("tenant_A credential must NOT appear in tenant_B list")
                .isFalse();

        // 5. DELETE via tenant_B path → must be rejected with 4xx + code=A002
        // Use plain RestTemplate (not TestRestTemplate) so the exception carries the response body.
        // Transfer auth cookies & CSRF header from the TestRestTemplate session.
        HttpHeaders deleteHeaders = new HttpHeaders();
        deleteHeaders.set(HttpHeaders.COOKIE, auth.getFirst(HttpHeaders.COOKIE));
        deleteHeaders.set("X-XSRF-TOKEN", auth.getFirst("X-XSRF-TOKEN"));
        deleteHeaders.setContentType(MediaType.APPLICATION_JSON);

        assertThatThrownBy(() ->
                http.exchange(
                        url("/admin/api/tenants/" + tenantBId + "/credentials/" + credentialIdB64),
                        HttpMethod.DELETE,
                        new HttpEntity<>(deleteHeaders),
                        JsonNode.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode().is4xxClientError())
                            .as("cross-tenant DELETE must be 4xx, got %s", ex.getStatusCode())
                            .isTrue();
                    JsonNode errorBody = om.readTree(ex.getResponseBodyAsString());
                    assertThat(errorBody.get("success").asBoolean())
                            .as("success must be false for access denied")
                            .isFalse();
                    assertThat(errorBody.get("code").asText())
                            .as("error code must be A002 (ACCESS_DENIED)")
                            .isEqualTo("A002");
                });

        // 6. Credential still exists — tenant_A list still contains it after failed cross-tenant attempt
        ResponseEntity<JsonNode> listAAfter = rest.exchange(
                url("/admin/api/tenants/" + tenantAId + "/credentials"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        boolean stillInA = StreamSupport.stream(
                        unwrap(listAAfter).get("content").spliterator(), false)
                .anyMatch(n -> credentialIdB64.equals(n.get("credentialId").asText()));
        assertThat(stillInA)
                .as("credential must still exist in tenant_A after rejected cross-tenant DELETE")
                .isTrue();
    }
}
