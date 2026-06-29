package com.crosscert.passkey.admin.tenant;

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
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PUT /admin/api/tenants/{id} happy path + audit log row 생성 검증.
 *
 * AdminFlowIT 의 loginAs 가 private 이므로 Testcontainers 셋업과
 * 로그인 helper 를 inline 으로 복사한다.
 *
 * 시나리오:
 *   1. tenant 생성 (POST)
 *   2. PUT 으로 displayName + allowedOrigins 변경
 *   3. GET 으로 변경 확인
 *   4. GET /audit?action=TENANT_UPDATE → row + payload.changedFields 검증
 *   5. PUT 동일 body 재호출 → audit row 추가 안 됨 (no-op)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TenantAdminControllerUpdateIT {

    // ----------------------------------------------------------------
    // Containers (same config as AdminFlowIT)
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
            ds.setPoolName("tenant-update-it-owner");
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
    // Test
    // ----------------------------------------------------------------

    @Test
    void putUpdatesFields_andAppendsAuditRow_withChangedFields() throws Exception {
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // 1. tenant 생성
        String createBody = """
                {"slug":"update-it","displayName":"Original Name","rpId":"localhost","rpName":"Original RP",
                 "allowedOrigins":["http://localhost:9090"],
                 "acceptedFormats":["none","packed"],
                 "requireUserVerification":true,
                 "mdsRequired":false,
                 "attestationConveyance":"NONE",
                 "webauthnTimeoutMs":60000}
                """;
        ResponseEntity<JsonNode> createRes = rest.exchange(
                url("/admin/api/tenants"),
                HttpMethod.POST,
                new HttpEntity<>(createBody, auth),
                JsonNode.class);
        assertThat(createRes.getStatusCode().is2xxSuccessful())
                .as("create tenant: %s body=%s", createRes.getStatusCode(), createRes.getBody())
                .isTrue();
        String tenantId = unwrap(createRes).get("id").asText();

        // 2. PUT — displayName + allowedOrigins 변경
        String updateBody = """
                {"displayName":"Updated Name","rpId":"localhost","rpName":"Original RP",
                 "allowedOrigins":["http://localhost:9090","http://localhost:9091"],
                 "acceptedFormats":["none","packed"],
                 "requireUserVerification":true,
                 "mdsRequired":false,
                 "attestationConveyance":"NONE",
                 "webauthnTimeoutMs":60000}
                """;
        ResponseEntity<JsonNode> putRes = rest.exchange(
                url("/admin/api/tenants/" + tenantId),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, auth),
                JsonNode.class);
        assertThat(putRes.getStatusCode().is2xxSuccessful())
                .as("PUT tenant: %s body=%s", putRes.getStatusCode(), putRes.getBody())
                .isTrue();
        assertThat(unwrap(putRes).get("displayName").asText()).isEqualTo("Updated Name");

        // 3. GET 확인
        ResponseEntity<JsonNode> getRes = rest.exchange(
                url("/admin/api/tenants/" + tenantId),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        JsonNode getData = unwrap(getRes);
        assertThat(getData.get("displayName").asText()).isEqualTo("Updated Name");
        assertThat(StreamSupport.stream(getData.get("allowedOrigins").spliterator(), false))
                .anyMatch(n -> n.asText().equals("http://localhost:9091"));

        // 4. audit row 검증 — TENANT_UPDATE filter
        ResponseEntity<JsonNode> auditRes = rest.exchange(
                url("/admin/api/audit?action=TENANT_UPDATE"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        JsonNode rows = unwrap(auditRes);
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size())
                .as("TENANT_UPDATE audit rows")
                .isGreaterThanOrEqualTo(1);

        // 이 tenant 의 row 찾기
        JsonNode matchingRow = StreamSupport.stream(rows.spliterator(), false)
                .filter(r -> tenantId.equals(r.get("targetId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "TENANT_UPDATE row for tenant " + tenantId + " not found in " + rows));
        assertThat(matchingRow.get("action").asText()).isEqualTo("TENANT_UPDATE");

        // payload 는 String (AuditLogView.payload 가 String) — JSON 파싱
        JsonNode payloadNode = matchingRow.get("payload");
        JsonNode payload = payloadNode.isTextual()
                ? om.readTree(payloadNode.asText())
                : payloadNode;

        JsonNode changedFields = payload.get("changedFields");
        assertThat(changedFields)
                .as("payload.changedFields must be present: %s", payload)
                .isNotNull();
        List<String> changedList = StreamSupport.stream(changedFields.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertThat(changedList).contains("displayName", "allowedOrigins");

        // 5. PUT 동일 body 재호출 → audit row 추가 안 됨 (no-op)
        int auditCountBefore = rows.size();
        ResponseEntity<JsonNode> putAgain = rest.exchange(
                url("/admin/api/tenants/" + tenantId),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, auth),
                JsonNode.class);
        assertThat(putAgain.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<JsonNode> auditAgain = rest.exchange(
                url("/admin/api/audit?action=TENANT_UPDATE"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        assertThat(unwrap(auditAgain).size())
                .as("no-op PUT must not append audit row")
                .isEqualTo(auditCountBefore);
    }
}
