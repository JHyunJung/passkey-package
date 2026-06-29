package com.crosscert.passkey.admin.activity;

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
 * T7 — ActivityControllerIT: PLATFORM_OPERATOR Activity 페이지 회귀 채널 (자동 IT 1/2).
 *
 * <p>alice (PLATFORM_OPERATOR) 로 단일 시나리오 5 assertion. KPI / Top5 /
 * sinceId polling 정상 동작 보장 — service boundary 또는 repository tuple
 * WHERE 가 깨지면 즉시 회귀 탐지.
 *
 * <p>Testcontainers + loginAs inline 패턴은 RpAdminBoundaryIT 에서 복사.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ActivityControllerIT {

    // ----------------------------------------------------------------
    // Containers (identical to RpAdminBoundaryIT / PlatformOperatorUnrestrictedIT)
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
            ds.setPoolName("activity-controller-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    // ----------------------------------------------------------------
    // State reset — alice stays PLATFORM_OPERATOR, bob stays RP_ADMIN(demo-rp).
    // Mirrors the FK-safe pattern from RpAdminBoundaryIT (V23 fk_admin_user_tenant
    // blocks DELETE FROM tenant while child rows exist).
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
        jdbc.update("DELETE FROM APP_OWNER.admin_user_tenant");
        jdbc.update("UPDATE APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
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
        // bob → RP_ADMIN @ demo-rp (mirrors V23 step 9)
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'RP_ADMIN',
                       tenant_id = HEXTORAW('0000000000000000000000000000C0DE')
                 WHERE email = 'bob@crosscert.com'
                """);
        // alice → PLATFORM_OPERATOR (no tenant)
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'PLATFORM_OPERATOR',
                       tenant_id = NULL
                 WHERE email = 'alice@crosscert.com'
                """);
        var redisConn = redisFactory.getConnection();
        try {
            redisConn.serverCommands().flushAll();
        } finally {
            redisConn.close();
        }
    }

    // ----------------------------------------------------------------
    // Login helpers (inlined from RpAdminBoundaryIT.loginAs)
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

    /**
     * Triggers the {@code AuthenticationFailureHandler} path so an
     * ADMIN_LOGIN_FAILED audit row is appended. Uses TestRestTemplate (not the
     * field {@code http}) so the 401 response body doesn't throw.
     */
    private void attemptLoginFailure(String email, String wrongPassword) {
        Map<String, String> jar = new LinkedHashMap<>();
        ResponseEntity<String> seed = rest.exchange(
                url("/admin/api/me"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        mergeSetCookiesByName(jar, seed.getHeaders().get(HttpHeaders.SET_COOKIE));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        h.set(HttpHeaders.COOKIE, renderCookieHeader(jar));
        h.set("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        String body = "email=" + email + "&password=" + wrongPassword;
        ResponseEntity<String> resp = rest.exchange(
                url("/admin/login"), HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertThat(resp.getStatusCode().value())
                .as("attempt login %s expected 401, got %s", email, resp.getStatusCode())
                .isEqualTo(401);
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

    private void issueApiKey(HttpHeaders auth, String tenantId, String name) throws Exception {
        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "name", name,
                "scopes", List.of("registration", "authentication"));
        ResponseEntity<JsonNode> res = rest.exchange(
                url("/admin/api/api-keys"),
                HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(body), auth),
                JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful())
                .as("issue api-key tenant=%s: %s body=%s", tenantId, res.getStatusCode(), res.getBody())
                .isTrue();
    }

    // ----------------------------------------------------------------
    // Test — single scenario, 5 assertion groups
    // ----------------------------------------------------------------

    @Test
    void activitySnapshotKpiTop5Feed() throws Exception {
        // ─── 1. alice (PLATFORM_OPERATOR) seeds activity ────────────────────
        // - 2 tenants (TENANT_CREATE x2) — each row carries tenant_id
        // - 1 api-key on tenant-a (API_KEY_ISSUE)
        // - alice's own login (ADMIN_LOGIN, tenant_id=null)
        // - bob wrong-pw attempt (ADMIN_LOGIN_FAILED, tenant_id=null)
        // → 5+ audit rows in the 24h window, 3+ ops, 1+ security.
        HttpHeaders aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw");
        String tenantAId = createTenantViaApi(aliceAuth, "activity-it-a", "Tenant A");
        createTenantViaApi(aliceAuth, "activity-it-b", "Tenant B");
        issueApiKey(aliceAuth, tenantAId, "activity-key-a");

        attemptLoginFailure("bob@crosscert.com", "wrong-pw-xyz");

        // ─── 2. GET /admin/api/activity ────────────────────────────────────
        ResponseEntity<JsonNode> resp1 = rest.exchange(
                url("/admin/api/activity"), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(resp1.getStatusCode().is2xxSuccessful())
                .as("GET /admin/api/activity: %s body=%s", resp1.getStatusCode(), resp1.getBody())
                .isTrue();
        JsonNode data = resp1.getBody().get("data");

        // ── Assertion 1: KPI counts + p95Ms null ─────────────────────────
        JsonNode kpi = data.get("kpi");
        assertThat(kpi.get("events24h").asLong())
                .as("events24h must cover all seeded rows: %s", data)
                .isGreaterThanOrEqualTo(5L);
        assertThat(kpi.get("ops24h").asLong())
                .as("ops24h covers TENANT_CREATE x2 + API_KEY_ISSUE + ADMIN_LOGIN")
                .isGreaterThanOrEqualTo(3L);
        assertThat(kpi.get("security24h").asLong())
                .as("security24h covers ADMIN_LOGIN_FAILED")
                .isGreaterThanOrEqualTo(1L);
        // p95Ms is null → Jackson default-property-inclusion: non_null omits it.
        JsonNode p95 = kpi.path("p95Ms");
        assertThat(p95.isNull() || p95.isMissingNode())
                .as("p95Ms must be absent/null (no Micrometer instrumentation yet): %s", kpi)
                .isTrue();

        // ── Assertion 2: Top5 contains tenant-a, excludes NULL-tenant rows ─
        JsonNode top5 = data.get("top5");
        assertThat(top5.isArray()).isTrue();
        boolean tenantAFound = false;
        for (JsonNode t : top5) {
            // None of the top5 entries may have null tenantId (NULL rows are
            // filtered by ActivityRepository.topTenantsSinceRaw, and Jackson
            // non_null serialization would omit the field if it were null).
            JsonNode tidNode = t.path("tenantId");
            assertThat(tidNode.isMissingNode() || tidNode.isNull())
                    .as("top5 must not contain NULL-tenant rows: %s", t)
                    .isFalse();
            if (tenantAId.equals(tidNode.asText())) {
                tenantAFound = true;
            }
        }
        assertThat(tenantAFound)
                .as("top5 must contain tenant-a (it had TENANT_CREATE + API_KEY_ISSUE): %s", top5)
                .isTrue();

        // ── Assertion 3: Feed sorted DESC by (createdAt, id) ─────────────
        JsonNode feed = data.get("feed");
        assertThat(feed.size())
                .as("feed must have at least 5 rows: %s", feed)
                .isGreaterThanOrEqualTo(5);
        for (int i = 1; i < feed.size(); i++) {
            String prev = feed.get(i - 1).get("createdAt").asText();
            String curr = feed.get(i).get("createdAt").asText();
            assertThat(prev.compareTo(curr))
                    .as("feed[%d].createdAt=%s should be >= feed[%d].createdAt=%s",
                            i - 1, prev, i, curr)
                    .isGreaterThanOrEqualTo(0);
        }

        // ─── 3. Polling with sinceId={feed[0].id} → empty feed ──────────────
        String firstId = feed.get(0).get("id").asText();
        ResponseEntity<JsonNode> resp2 = rest.exchange(
                url("/admin/api/activity?sinceId=" + firstId), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(resp2.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode feed2 = resp2.getBody().get("data").get("feed");

        // ── Assertion 4: sinceId=newest → empty feed (idempotent polling) ─
        assertThat(feed2.size())
                .as("feed with sinceId=newest must be empty (tuple WHERE works): %s", feed2)
                .isZero();

        // ─── 4. bob normal login (new ADMIN_LOGIN row) → poll picks it up ──
        loginAs("bob@crosscert.com", "bob-temp-pw");
        ResponseEntity<JsonNode> resp3 = rest.exchange(
                url("/admin/api/activity?sinceId=" + firstId), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(resp3.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode feed3 = resp3.getBody().get("data").get("feed");

        // ── Assertion 5: New events after sinceId visible to poll ────────
        assertThat(feed3.size())
                .as("new ADMIN_LOGIN row should appear when polling with old sinceId: %s", feed3)
                .isGreaterThanOrEqualTo(1);
    }

    // ----------------------------------------------------------------
    // detail — GET /admin/api/activity/{id}
    // ----------------------------------------------------------------

    @Test
    void detail_returnsPayload_forPlatformOperator() {
        // demo-rp(...C0DE)에 payload 있는 행 1건. RAW 0000...DA01 = UUID 00000000-0000-0000-0000-0000000000da01
        ownerJdbc().update("""
                INSERT INTO APP_OWNER.audit_log
                    (id, prev_hash, hash, actor_id, actor_email, action,
                     target_type, target_id, payload, created_at, updated_at,
                     tenant_id, tenant_prev_hash, tenant_hash)
                VALUES (HEXTORAW('0000000000000000000000000000DA01'), NULL, SYS_GUID(), NULL, 'admin@acme.com',
                     'WEBAUTHN_CONFIG_UPDATED', 'TENANT', 'demo-rp',
                     '{"before":{"uv":false},"after":{"uv":true}}',
                     SYSTIMESTAMP, SYSTIMESTAMP,
                     HEXTORAW('0000000000000000000000000000C0DE'), NULL, SYS_GUID())
                """);

        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");
        ResponseEntity<String> resp = rest.exchange(
                url("/admin/api/activity/00000000-0000-0000-0000-00000000da01"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = readActivityJson(resp.getBody());
        assertThat(body.get("action").asText()).isEqualTo("WEBAUTHN_CONFIG_UPDATED");
        assertThat(body.get("payload").asText()).contains("\"after\"");
        assertThat(body.get("tenantSlug").asText()).isEqualTo("demo-rp");
    }

    @Test
    void detail_isForbidden_forRpAdmin() {
        ownerJdbc().update("""
                INSERT INTO APP_OWNER.audit_log
                    (id, prev_hash, hash, actor_id, actor_email, action,
                     target_type, target_id, payload, created_at, updated_at,
                     tenant_id, tenant_prev_hash, tenant_hash)
                VALUES (HEXTORAW('0000000000000000000000000000DA02'), NULL, SYS_GUID(), NULL, 'x@x.com', 'API_KEY_ISSUE',
                     'API_KEY', 'pk_x', '{}', SYSTIMESTAMP, SYSTIMESTAMP,
                     HEXTORAW('0000000000000000000000000000C0DE'), NULL, SYS_GUID())
                """);
        HttpHeaders auth = loginAs("bob@crosscert.com", "bob-temp-pw");
        ResponseEntity<String> resp = rest.exchange(
                url("/admin/api/activity/00000000-0000-0000-0000-00000000da02"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void detail_isNotFound_forUnknownId() {
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");
        ResponseEntity<String> resp = rest.exchange(
                url("/admin/api/activity/00000000-0000-0000-0000-00000000dead"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    /** ApiResponse envelope이면 data를 벗기고, 아니면 root 반환. */
    private JsonNode readActivityJson(String body) {
        try {
            JsonNode root = om.readTree(body);
            return root.has("data") ? root.get("data") : root;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
