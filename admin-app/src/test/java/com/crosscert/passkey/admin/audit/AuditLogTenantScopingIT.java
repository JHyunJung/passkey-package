package com.crosscert.passkey.admin.audit;

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
 * T8 — AuditLogTenantScopingIT: RP_ADMIN cross-tenant 차단 회귀 채널 (자동 IT 2/2).
 *
 * <p>bob (RP_ADMIN, demo-rp) 가 GET /admin/api/audit 을 호출했을 때:
 * <ul>
 *   <li>{@code tenantId=demoRp} (self) → 200, demo-rp row 만</li>
 *   <li>{@code tenantId} 생략 → 200, service auto-scope = demo-rp</li>
 *   <li>{@code tenantId=tenantA} (other) → 403 ACCESS_DENIED</li>
 * </ul>
 *
 * <p>service boundary 또는 controller scope 분기 누락 시 즉시 회귀 탐지.
 *
 * <p>Note (Jackson non_null + AuditLogRepository.search 의 WHERE 절):
 * RP_ADMIN 의 effectiveTenantId = scope.get() (demo-rp) 라서 query 는
 * {@code a.tenantId = :tenantId} 가 되고 {@code tenant_id IS NULL} row 는
 * 제외된다. 따라서 응답의 모든 row 는 {@code tenantId == demoRp} 여야 한다
 * (null 가능성 없음). Jackson {@code default-property-inclusion: non_null} 이
 * 적용되어도, tenantId 가 항상 non-null 이므로 missing-node 도 발생 안 한다.
 *
 * <p>Testcontainers + loginAs inline 패턴은 RpAdminBoundaryIT 에서 복사.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuditLogTenantScopingIT {

    // ----------------------------------------------------------------
    // Containers (identical to ActivityControllerIT / RpAdminBoundaryIT)
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
            ds.setPoolName("audit-scoping-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    // ----------------------------------------------------------------
    // State reset — bob @ demo-rp RP_ADMIN, alice PLATFORM_OPERATOR.
    // Mirrors RpAdminBoundaryIT's FK-safe pattern (V23 fk_admin_user_tenant).
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
        // bob → RP_ADMIN @ demo-rp (role + admin_user_tenant 매핑; N:M 전환).
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
        // alice → PLATFORM_OPERATOR (매핑 0개 = 전체 접근)
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'PLATFORM_OPERATOR'
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

        ResponseEntity<String> seed = rest.exchange(
                url("/admin/api/me"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        mergeSetCookiesByName(jar, seed.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertThat(jar.get("XSRF-TOKEN"))
                .as("CSRF cookie should be emitted on seed request (even 401)")
                .isNotNull();

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

    private String lookupTenantIdBySlug(HttpHeaders auth, String slug) {
        ResponseEntity<JsonNode> res = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.GET,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful())
                .as("GET /admin/api/tenants: %s body=%s", res.getStatusCode(), res.getBody())
                .isTrue();
        return StreamSupport
                .stream(res.getBody().get("data").spliterator(), false)
                .filter(n -> slug.equals(n.get("slug").asText()))
                .map(n -> n.get("id").asText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("tenant slug=" + slug + " not found"));
    }

    /** Asserts the call throws HttpClientErrorException with HTTP 403. */
    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e)
                        .getStatusCode().value())
                        .isEqualTo(403));
    }

    // ----------------------------------------------------------------
    // Test — RP_ADMIN cross-tenant audit boundary (5 assertions)
    // ----------------------------------------------------------------

    @Test
    void rpAdminCanReadOwnTenantAuditOnly() throws Exception {
        // ─── 1. alice (PLATFORM_OPERATOR) seeds tenant_A ──────────────────
        // TENANT_CREATE audit row 의 tenant_id = tenant_A.id 가 기록된다.
        HttpHeaders aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw");
        String tenantA = createTenantViaApi(aliceAuth, "tenant-a-scoping", "Tenant A Scoping");
        String demoRp = lookupTenantIdBySlug(aliceAuth, "demo-rp");

        // tenant_id = demoRp 가 달린 audit row 를 만들기 위해 demo-rp 도 PUT
        // (TENANT_UPDATE → tenant_id = demoRp). 그렇지 않으면 demo-rp 관련
        // audit row 가 없어 assertion 이 vacuous 해진다.
        Map<String, Object> updateBody = Map.of(
                "displayName", "Demo RP Updated",
                "rpId", "localhost",
                "rpName", "Demo RP",
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false,
                "attestationConveyance", "NONE",
                "webauthnTimeoutMs", 60000);
        ResponseEntity<JsonNode> upd = rest.exchange(
                url("/admin/api/tenants/" + demoRp), HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(updateBody), aliceAuth),
                JsonNode.class);
        assertThat(upd.getStatusCode().is2xxSuccessful())
                .as("seed demo-rp TENANT_UPDATE: %s body=%s", upd.getStatusCode(), upd.getBody())
                .isTrue();

        // ─── 2. bob (RP_ADMIN, demo-rp) 로그인 ────────────────────────────
        HttpHeaders bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw");

        // ── Assertion 1 + 2: GET /audit?tenantId=demoRp → 200 + only demo-rp rows
        ResponseEntity<JsonNode> r3 = http.exchange(
                url("/admin/api/audit?tenantId=" + demoRp), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(r3.getStatusCode().is2xxSuccessful())
                .as("GET /audit?tenantId=demoRp: %s body=%s", r3.getStatusCode(), r3.getBody())
                .isTrue();
        JsonNode rows3 = r3.getBody().get("data");
        // 의미 있는 회귀 채널이 되려면 최소 1 row 가 있어야 한다 (vacuous truth 방지).
        assertThat(rows3.size())
                .as("at least one demo-rp audit row should exist after TENANT_UPDATE seed: %s", rows3)
                .isGreaterThanOrEqualTo(1);
        for (JsonNode row : rows3) {
            // RP_ADMIN effectiveTenantId = demoRp → WHERE tenant_id = :demoRp
            // (tenant_id IS NULL row 는 제외). 모든 row 의 tenantId 는 정확히 demoRp.
            JsonNode tidNode = row.path("tenantId");
            assertThat(tidNode.isMissingNode() || tidNode.isNull())
                    .as("tenantId must be present (RP_ADMIN scope query excludes NULL): %s", row)
                    .isFalse();
            assertThat(tidNode.asText())
                    .as("every row must have tenantId == demoRp (no cross-tenant leak): %s", row)
                    .isEqualTo(demoRp);
        }

        // ── Assertion 3 + 4: GET /audit (생략) → 200 + auto-scope to demo-rp
        ResponseEntity<JsonNode> r4 = http.exchange(
                url("/admin/api/audit"), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(r4.getStatusCode().is2xxSuccessful())
                .as("GET /audit (no param): %s body=%s", r4.getStatusCode(), r4.getBody())
                .isTrue();
        JsonNode rows4 = r4.getBody().get("data");
        assertThat(rows4.size())
                .as("at least one demo-rp audit row should appear under auto-scope: %s", rows4)
                .isGreaterThanOrEqualTo(1);
        for (JsonNode row : rows4) {
            JsonNode tidNode = row.path("tenantId");
            assertThat(tidNode.isMissingNode() || tidNode.isNull())
                    .as("auto-scope must enforce tenant_id = demoRp (NULL excluded): %s", row)
                    .isFalse();
            assertThat(tidNode.asText())
                    .as("auto-scope: every row must be demoRp (no tenant_A leak): %s", row)
                    .isEqualTo(demoRp);
        }

        // ── Assertion 5: GET /audit?tenantId=tenantA → 403 ACCESS_DENIED
        assertForbidden(() -> http.exchange(
                url("/admin/api/audit?tenantId=" + tenantA), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class));
    }
}
