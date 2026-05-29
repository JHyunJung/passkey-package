package com.crosscert.passkey.admin.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * T14 — PlatformOperatorUnrestrictedIT: PLATFORM_OPERATOR 무제한 동작 검증 (자동 IT 2).
 *
 * <p>alice (PLATFORM_OPERATOR) 로 10 assertion 실행. role 변경이 기존 운영 능력을
 * 안 깨뜨림을 보장하는 회귀 채널.
 *
 * <p>Testcontainers + loginAs inline 패턴은 T13 RpAdminBoundaryIT 에서 복사.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PlatformOperatorUnrestrictedIT {

    // ----------------------------------------------------------------
    // Containers (identical to RpAdminBoundaryIT / AdminFlowIT)
    // ----------------------------------------------------------------

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
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

    // ----------------------------------------------------------------
    // State reset — alice remains PLATFORM_OPERATOR (no tenant), clean slate
    // ----------------------------------------------------------------

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
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
        // Ensure alice is PLATFORM_OPERATOR with no tenant. (MFA is disabled
        // for the seed users by the test-only migration V9000 so logins here
        // are not gated by MfaPendingFilter; see db/testfix.)
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'PLATFORM_OPERATOR',
                       tenant_id = NULL
                 WHERE email = 'alice@crosscert.com'
                """);
        // Flush Redis to clear scheduler leases and session state
        var redisConn = redisFactory.getConnection();
        try {
            redisConn.serverCommands().flushAll();
        } finally {
            redisConn.close();
        }
    }

    // ----------------------------------------------------------------
    // Login helper (inlined from RpAdminBoundaryIT.loginAs)
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

    // ----------------------------------------------------------------
    // Test — 10 assertions
    // ----------------------------------------------------------------

    @Test
    void platformOperator_canAccessAllTenants_andRunPlatformOperations() throws Exception {
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // 1. GET /me → PLATFORM_OPERATOR, tenantId=null
        ResponseEntity<JsonNode> me = http.exchange(
                url("/admin/api/me"), HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(me.getBody().get("data").get("role").asText()).isEqualTo("PLATFORM_OPERATOR");
        // tenantId is null for PLATFORM_OPERATOR — NON_NULL serialization omits the field entirely
        JsonNode tenantIdNode = me.getBody().get("data").path("tenantId");
        assertThat(tenantIdNode.isNull() || tenantIdNode.isMissingNode())
                .as("PLATFORM_OPERATOR tenantId must be null/absent").isTrue();

        // 2. tenant_A 생성
        String tenantAId = createTenantViaApi(auth, "platform-it-a", "Tenant A");

        // 3. GET /tenants — demo-rp + platform-it-a 모두
        ResponseEntity<JsonNode> tList = http.exchange(
                url("/admin/api/tenants"), HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        List<String> slugs = StreamSupport
                .stream(tList.getBody().get("data").spliterator(), false)
                .map(n -> n.get("slug").asText()).toList();
        assertThat(slugs).contains("demo-rp", "platform-it-a");

        // 4. PUT /tenants/{tenant_A} → 200
        Map<String, Object> updateA = Map.of(
                "displayName", "Updated A",
                "rpId", "localhost",
                "rpName", "RP for platform-it-a",
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false,
                "attestationConveyance", "NONE",
                "webauthnTimeoutMs", 60000);
        ResponseEntity<JsonNode> putA = http.exchange(
                url("/admin/api/tenants/" + tenantAId), HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(updateA), auth),
                JsonNode.class);
        assertThat(putA.getStatusCode().is2xxSuccessful()).isTrue();

        // 5. PUT /tenants/{demo-rp} → 200 (RP 의 tenant 도 자유)
        String demoRpId = StreamSupport
                .stream(tList.getBody().get("data").spliterator(), false)
                .filter(n -> "demo-rp".equals(n.get("slug").asText()))
                .findFirst().orElseThrow()
                .get("id").asText();
        Map<String, Object> updateDemo = Map.of(
                "displayName", "Demo RP (updated by alice)",
                "rpId", "localhost",
                "rpName", "Demo RP",
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false,
                "attestationConveyance", "NONE",
                "webauthnTimeoutMs", 60000);
        ResponseEntity<JsonNode> putDemo = http.exchange(
                url("/admin/api/tenants/" + demoRpId), HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(updateDemo), auth),
                JsonNode.class);
        assertThat(putDemo.getStatusCode().is2xxSuccessful()).isTrue();

        // 6. GET /tenants/{demo-rp}/credentials → 200
        ResponseEntity<JsonNode> credsDemo = http.exchange(
                url("/admin/api/tenants/" + demoRpId + "/credentials"),
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(credsDemo.getStatusCode().is2xxSuccessful()).isTrue();

        // 7. POST /api-keys {tenantId: demo-rp} → 200
        Map<String, Object> keyForDemo = Map.of(
                "tenantId", demoRpId,
                "name", "alice-key-for-demo",
                "scopes", List.of("registration"));
        ResponseEntity<JsonNode> keyDemo = http.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(keyForDemo), auth),
                JsonNode.class);
        assertThat(keyDemo.getStatusCode().is2xxSuccessful()).isTrue();

        // 8. POST /keys/rotate → 200
        ResponseEntity<JsonNode> rotate = http.exchange(
                url("/admin/api/keys/rotate"), HttpMethod.POST,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(rotate.getStatusCode().is2xxSuccessful()).isTrue();

        // 9. GET /mds/status → 200
        ResponseEntity<JsonNode> mdsStatus = http.exchange(
                url("/admin/api/mds/status"), HttpMethod.GET,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(mdsStatus.getStatusCode().is2xxSuccessful()).isTrue();

        // 10. GET /audit → 200
        ResponseEntity<JsonNode> audit = http.exchange(
                url("/admin/api/audit"), HttpMethod.GET,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(audit.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
