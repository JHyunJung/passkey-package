package com.crosscert.passkey.admin.auth;

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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T13 — RpAdminBoundaryIT: cross-tenant 차단 회귀 채널 (자동 IT 1).
 *
 * <p>bob (RP_ADMIN, demo-rp) 로 14 assertion 실행. service boundary 또는
 * {@code @PreAuthorize} 누락 시 200 으로 즉시 회귀 탐지.
 *
 * <p>Testcontainers + loginAs inline 패턴은 AdminFlowIT 에서 복사.
 * resetState() 는 T12 AdminFlowIT 패턴 (bob 을 매 test 시작 시 RP_ADMIN(demo-rp)
 * 으로 reset) 그대로 사용.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class RpAdminBoundaryIT {

    // ----------------------------------------------------------------
    // Containers (identical to AdminFlowIT / TenantAdminControllerUpdateIT)
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

    /** Plain RestTemplate so 4xx throws HttpClientErrorException (not swallowed). */
    private final RestTemplate http = new RestTemplate();

    JdbcTemplate jdbc;

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
            ds.setPoolName("rp-admin-boundary-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    // ----------------------------------------------------------------
    // State reset — copies AdminFlowIT.resetState() (T12 FK pattern):
    //   bob is re-assigned to demo-rp as RP_ADMIN each test.
    // ----------------------------------------------------------------

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        // audit_log: APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy_entry");
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy");
        // NULL out admin_user.tenant_id before deleting tenants (V23 FK —
        // fk_admin_user_tenant blocks DELETE FROM tenant while child rows exist)
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
        // Re-seed demo-rp tenant (seeded by V23; deleted above for clean slate).
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
        // Re-seed demo-rp aaguid policy (ANY, mds_strict=N) — matches V26 backfill pattern.
        // resetState() inserts the tenant via raw SQL (not createTenantViaApi), so the
        // TenantAdminService auto-create is not triggered; we must seed it explicitly.
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_aaguid_policy
                    (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
                VALUES (HEXTORAW('0000000000000000000000000000C0DE'),
                    'ANY', 'N', SYSTIMESTAMP, SYSTIMESTAMP, 'test:reset')
                """);
        // Re-assign bob to demo-rp as RP_ADMIN (mirrors V23 step 9)
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'RP_ADMIN',
                       tenant_id = HEXTORAW('0000000000000000000000000000C0DE')
                 WHERE email = 'bob@crosscert.com'
                """);
        var redisConn = redisFactory.getConnection();
        try {
            redisConn.serverCommands().flushAll();
        } finally {
            redisConn.close();
        }
    }

    // ----------------------------------------------------------------
    // Login helper (inlined from AdminFlowIT.loginAs)
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

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String createTenantViaApi(HttpHeaders auth, String slug, String displayName) throws Exception {
        Map<String, Object> body = Map.of(
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
                new HttpEntity<>(om.writeValueAsString(body), auth),
                JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful())
                .as("create tenant slug=%s: %s body=%s", slug, res.getStatusCode(), res.getBody())
                .isTrue();
        return res.getBody().get("data").get("id").asText();
    }

    /**
     * Asserts that the given call throws {@link HttpClientErrorException} with HTTP 403.
     * Uses a plain RestTemplate (set as field {@code http}) so 4xx is not swallowed.
     */
    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e)
                        .getStatusCode().value())
                        .isEqualTo(403));
    }

    // ----------------------------------------------------------------
    // Test — 14 assertions
    // ----------------------------------------------------------------

    @Test
    void rpAdmin_seesAndMutatesOnlyOwnTenant_andCannotPlatformOperations() throws Exception {
        // 사전: alice (PLATFORM_OPERATOR) 로 tenant_A 생성
        HttpHeaders aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw");
        String tenantAId = createTenantViaApi(aliceAuth, "boundary-it-a", "Tenant A");

        // bob (RP_ADMIN, demo-rp) 로그인
        HttpHeaders bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw");

        // ── 1. GET /me — role=RP_ADMIN + tenantId ──────────────────────────────
        ResponseEntity<JsonNode> me = http.exchange(
                url("/admin/api/me"), HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(me.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode meData = me.getBody().get("data");
        assertThat(meData.get("role").asText())
                .as("bob must have role RP_ADMIN")
                .isEqualTo("RP_ADMIN");
        String myTenantId = meData.get("tenantId").asText();
        assertThat(myTenantId)
                .as("bob must have a tenantId")
                .isNotBlank();

        // ── 2. GET /tenants — demo-rp 만 포함, boundary-it-a 미포함 ───────────
        ResponseEntity<JsonNode> tList = http.exchange(
                url("/admin/api/tenants"), HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(tList.getStatusCode().is2xxSuccessful()).isTrue();
        List<String> slugs = StreamSupport
                .stream(tList.getBody().get("data").spliterator(), false)
                .map(n -> n.get("slug").asText())
                .toList();
        assertThat(slugs)
                .as("bob sees only own tenant — actual slugs: %s", slugs)
                .contains("demo-rp")
                .doesNotContain("boundary-it-a");

        // ── 3. GET /tenants/{my} → 200 ────────────────────────────────────────
        ResponseEntity<JsonNode> myT = http.exchange(
                url("/admin/api/tenants/" + myTenantId), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(myT.getStatusCode().is2xxSuccessful())
                .as("bob GET own tenant must succeed")
                .isTrue();

        // ── 4. GET /tenants/{other} → 403 ────────────────────────────────────
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants/" + tenantAId), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class));

        // ── 5. PUT /tenants/{my} → 200 ────────────────────────────────────────
        Map<String, Object> updateBody = Map.of(
                "displayName", "Updated by Bob",
                "rpId", "localhost",
                "rpName", "Demo RP",
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false,
                "attestationConveyance", "NONE",
                "webauthnTimeoutMs", 60000);
        ResponseEntity<JsonNode> putMy = http.exchange(
                url("/admin/api/tenants/" + myTenantId), HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(updateBody), bobAuth),
                JsonNode.class);
        assertThat(putMy.getStatusCode().is2xxSuccessful())
                .as("bob PUT own tenant must succeed")
                .isTrue();

        // ── 6. PUT /tenants/{other} → 403 ────────────────────────────────────
        String updateBodyJson = om.writeValueAsString(updateBody);
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants/" + tenantAId), HttpMethod.PUT,
                new HttpEntity<>(updateBodyJson, bobAuth),
                JsonNode.class));

        // ── 7. GET /tenants/{my}/credentials → 200 ───────────────────────────
        ResponseEntity<JsonNode> credsMy = http.exchange(
                url("/admin/api/tenants/" + myTenantId + "/credentials"),
                HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(credsMy.getStatusCode().is2xxSuccessful())
                .as("bob GET own tenant credentials must succeed")
                .isTrue();

        // ── 8. GET /tenants/{other}/credentials → 403 ────────────────────────
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants/" + tenantAId + "/credentials"),
                HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class));

        // ── 9. POST /tenants → 403 (RP_ADMIN 은 tenant create 불가) ──────────
        Map<String, Object> newTenant = Map.of(
                "slug", "bob-attempt",
                "displayName", "Bob Attempt",
                "rpId", "localhost",
                "rpName", "X",
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none"),
                "requireUserVerification", true,
                "mdsRequired", false,
                "attestationConveyance", "NONE",
                "webauthnTimeoutMs", 60000);
        String newTenantJson = om.writeValueAsString(newTenant);
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(newTenantJson, bobAuth),
                JsonNode.class));

        // ── 10. POST /api-keys (my tenant) → 200 ─────────────────────────────
        Map<String, Object> myKey = Map.of(
                "tenantId", myTenantId,
                "name", "bob-key-own",
                "scopes", List.of("registration", "authentication"));
        ResponseEntity<JsonNode> keyMy = http.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(myKey), bobAuth),
                JsonNode.class);
        assertThat(keyMy.getStatusCode().is2xxSuccessful())
                .as("bob POST api-key for own tenant must succeed")
                .isTrue();

        // ── 11. POST /api-keys (other tenant) → 403 ──────────────────────────
        Map<String, Object> otherKey = Map.of(
                "tenantId", tenantAId,
                "name", "bob-key-other",
                "scopes", List.of("registration"));
        String otherKeyJson = om.writeValueAsString(otherKey);
        assertForbidden(() -> http.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(otherKeyJson, bobAuth),
                JsonNode.class));

        // ── 12. POST /keys/rotate → 403 ──────────────────────────────────────
        assertForbidden(() -> http.exchange(
                url("/admin/api/keys/rotate"), HttpMethod.POST,
                new HttpEntity<>(bobAuth), JsonNode.class));

        // ── 13. POST /mds/sync → 403 ─────────────────────────────────────────
        assertForbidden(() -> http.exchange(
                url("/admin/api/mds/sync"), HttpMethod.POST,
                new HttpEntity<>(bobAuth), JsonNode.class));

        // ── 14. GET /audit — semantics shift ─────────────────────────────────
        // admin-role-separation: GET /audit was PLATFORM_OPERATOR only → 403 for bob.
        // activity-page: hasAnyRole(PLATFORM_OPERATOR, RP_ADMIN) — bob now allowed,
        // but cross-tenant tenantId param → 403.
        //
        // (a) cross-tenant tenantId → 403 ACCESS_DENIED
        assertForbidden(() -> http.exchange(
                url("/admin/api/audit?tenantId=" + tenantAId), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class));
        // (b) no tenantId → 200 (service auto-scope to demo-rp)
        ResponseEntity<JsonNode> auditOwn = http.exchange(
                url("/admin/api/audit"), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(auditOwn.getStatusCode().is2xxSuccessful())
                .as("bob GET /audit (no param) must succeed (auto-scope to demo-rp)")
                .isTrue();

        // ── 15. GET /tenants/{my}/aaguid-policy → 200 ────────────────────────
        // demo-rp policy row is seeded by resetState(); own-tenant access must succeed.
        ResponseEntity<JsonNode> aaguidGetMy = http.exchange(
                url("/admin/api/tenants/" + myTenantId + "/aaguid-policy"),
                HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(aaguidGetMy.getStatusCode().is2xxSuccessful())
                .as("bob GET own tenant aaguid-policy must succeed")
                .isTrue();

        // ── 16. GET /tenants/{other}/aaguid-policy → 403 ─────────────────────
        // tenantAId is a different tenant — cross-tenant read must be blocked.
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants/" + tenantAId + "/aaguid-policy"),
                HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class));

        // ── 17. PUT /tenants/{my}/aaguid-policy → 200 ────────────────────────
        // Minimal valid UpdateRequest: mode=ANY, mdsStrict=false, entries=[].
        Map<String, Object> aaguidUpdateBody = Map.of(
                "mode", "ANY",
                "mdsStrict", false,
                "entries", List.of());
        ResponseEntity<JsonNode> aaguidPutMy = http.exchange(
                url("/admin/api/tenants/" + myTenantId + "/aaguid-policy"),
                HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(aaguidUpdateBody), bobAuth),
                JsonNode.class);
        assertThat(aaguidPutMy.getStatusCode().is2xxSuccessful())
                .as("bob PUT own tenant aaguid-policy must succeed")
                .isTrue();

        // ── 18. PUT /tenants/{other}/aaguid-policy → 403 ─────────────────────
        // Cross-tenant mutation must be blocked — this is the critical security assertion.
        String aaguidUpdateJson = om.writeValueAsString(aaguidUpdateBody);
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants/" + tenantAId + "/aaguid-policy"),
                HttpMethod.PUT,
                new HttpEntity<>(aaguidUpdateJson, bobAuth),
                JsonNode.class));
    }
}
