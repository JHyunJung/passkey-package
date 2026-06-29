package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.admin.AdminApplication;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 12 — MultiTenantOperatorIT: 멀티 RP 운영자 스위처·격리 통합 테스트.
 *
 * <p>bob (RP_ADMIN) 을 demo-rp 와 second-rp 두 테넌트에 매핑한 뒤 6단계 시나리오를 검증한다:
 * <ol>
 *   <li>로그인 후 {@code GET /admin/api/active-tenant} → {@code allowedTenantIds} 크기 = 2.</li>
 *   <li>초기 {@code activeTenantId} 는 두 UUID 중 정렬상 첫 번째(TreeSet.first()).</li>
 *   <li>{@code POST /admin/api/active-tenant {secondRp}} → 200, {@code activeTenantId} = secondRp.</li>
 *   <li>활성 RP = secondRp 일 때 {@code GET /admin/api/tenants} 가 secondRp 의 slug 만 반환
 *       — Hibernate {@code @Filter} 격리 확인.</li>
 *   <li>{@code POST /admin/api/active-tenant {randomUnmappedUuid}} → 403 ACCESS_DENIED.</li>
 *   <li>{@code GET /admin/api/tenants/{demo-rp-id}} — secondRp 활성 중에 demo-rp 직접 접근 → 403.</li>
 * </ol>
 *
 * <p>Testcontainers (Oracle XE 21 + Redis 7) 및 loginAs 패턴은 {@code AdminFlowIT} 과 동일.
 */
// classes 명시: auth 패키지에 MfaController*Test$SliceConfig(@SpringBootConfiguration) 2개가
// 있어 config 자동탐색이 "multiple @SpringBootConfiguration" 으로 깨진다(단독 실행 시). 메인 앱
// 클래스를 직접 지정해 자동탐색을 끈다(AdminFlowIT 은 root 패키지라 자동탐색이 바로 AdminApplication 을 찾음).
@SpringBootTest(classes = AdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class MultiTenantOperatorIT {

    // ----------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------

    /** demo-rp 테넌트 ID (V23 / resetState 시드). HEXTORAW('0000000000000000000000000000C0DE'). */
    private static final UUID DEMO_RP_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000C0DE");

    /**
     * second-rp 테넌트 ID.  UUID 값을 demo-rp(…C0DE) 보다 크게 잡아서
     * TreeSet.first() == demo-rp 임이 결정적이 되도록 한다.
     * 0000…C0DF > 0000…C0DE → demo-rp 가 정렬 첫 번째.
     * HEXTORAW('0000000000000000000000000000C0DF').
     */
    private static final UUID SECOND_RP_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000C0DF");

    // ----------------------------------------------------------------
    // Containers
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
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

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
            ds.setPoolName("multi-tenant-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    // ----------------------------------------------------------------
    // State reset
    // ----------------------------------------------------------------

    /**
     * 매 테스트 전 깨끗한 상태로 초기화한다.
     * AdminFlowIT.resetState() 의 FK 순서 패턴을 그대로 따른다.
     * <ul>
     *   <li>demo-rp (0x…C0DE) 재시드</li>
     *   <li>second-rp (0x…C0DF) 시드 — 두 번째 매핑용</li>
     *   <li>bob 을 RP_ADMIN + 두 테넌트에 매핑</li>
     * </ul>
     */
    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);

        // 감사 로그: APP_ADMIN 은 DELETE 권한 없음(V10 설계) → 스키마 소유자 풀 사용
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy_entry");
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        // FK: admin_user_tenant.tenant_id → tenant
        jdbc.update("DELETE FROM APP_OWNER.admin_user_tenant");
        jdbc.update("UPDATE APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role <> 'PLATFORM_OPERATOR'");
        jdbc.update("DELETE FROM APP_OWNER.tenant");

        // ── demo-rp (0x…C0DE) 재시드 ──────────────────────────────────────
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                    require_user_verification, mds_required, created_at, updated_at)
                VALUES (HEXTORAW('0000000000000000000000000000C0DE'),
                    'demo-rp', 'Demo RP', 'localhost', 'Demo RP', 'active', 'Y', 'N',
                    SYSTIMESTAMP, SYSTIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
                VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'),
                    'http://localhost:9090', 0)
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_accepted_format (id, tenant_id, format)
                VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'), 'none')
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_accepted_format (id, tenant_id, format)
                VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DE'), 'packed')
                """);
        // AAGUID 정책 기본값 (리스트 엔드포인트에 필요)
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_aaguid_policy
                    (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
                VALUES (HEXTORAW('0000000000000000000000000000C0DE'),
                    'ANY', 'N', SYSTIMESTAMP, SYSTIMESTAMP, 'test:reset')
                """);

        // ── second-rp (0x…C0DF) 시드 ──────────────────────────────────────
        // UUID 0000…C0DF > 0000…C0DE → TreeSet.first() == demo-rp (결정적 기본값)
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                    require_user_verification, mds_required, created_at, updated_at)
                VALUES (HEXTORAW('0000000000000000000000000000C0DF'),
                    'second-rp', 'Second RP', 'second.localhost', 'Second RP', 'active', 'Y', 'N',
                    SYSTIMESTAMP, SYSTIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
                VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DF'),
                    'http://localhost:9091', 0)
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_accepted_format (id, tenant_id, format)
                VALUES (SYS_GUID(), HEXTORAW('0000000000000000000000000000C0DF'), 'none')
                """);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_aaguid_policy
                    (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
                VALUES (HEXTORAW('0000000000000000000000000000C0DF'),
                    'ANY', 'N', SYSTIMESTAMP, SYSTIMESTAMP, 'test:reset')
                """);

        // ── bob = RP_ADMIN, 두 테넌트에 매핑 ─────────────────────────────
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'RP_ADMIN'
                 WHERE email = 'bob@crosscert.com'
                """);
        // 매핑 1: bob → demo-rp
        jdbc.update("""
                MERGE INTO APP_OWNER.admin_user_tenant t
                USING (SELECT HEXTORAW('00000000000000000000000000000011') AS aid,
                              HEXTORAW('0000000000000000000000000000C0DE') AS tid FROM dual) s
                   ON (t.admin_user_id = s.aid AND t.tenant_id = s.tid)
                 WHEN NOT MATCHED THEN
                   INSERT (admin_user_id, tenant_id, created_at, created_by)
                   VALUES (s.aid, s.tid, SYSTIMESTAMP, 'test:reset')
                """);
        // 매핑 2: bob → second-rp
        jdbc.update("""
                MERGE INTO APP_OWNER.admin_user_tenant t
                USING (SELECT HEXTORAW('00000000000000000000000000000011') AS aid,
                              HEXTORAW('0000000000000000000000000000C0DF') AS tid FROM dual) s
                   ON (t.admin_user_id = s.aid AND t.tenant_id = s.tid)
                 WHEN NOT MATCHED THEN
                   INSERT (admin_user_id, tenant_id, created_at, created_by)
                   VALUES (s.aid, s.tid, SYSTIMESTAMP, 'test:reset')
                """);

        // Redis 세션 초기화
        var redisConn = redisFactory.getConnection();
        try {
            redisConn.serverCommands().flushAll();
        } finally {
            redisConn.close();
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String url(String path) { return "http://localhost:" + port + path; }

    /** TestRestTemplate 은 4xx 를 삼킨다 — 403 단언에는 plain RestTemplate 사용. */
    private final RestTemplate http = new RestTemplate();

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
    // Test
    // ----------------------------------------------------------------

    /**
     * 멀티 RP 운영자(bob) 스위처·격리 6단계 시나리오.
     *
     * <p><b>단계 요약:</b>
     * <ol>
     *   <li>bob 로그인 후 GET /admin/api/active-tenant → allowedTenantIds 2개.</li>
     *   <li>초기 activeTenantId == TreeSet.first() (두 UUID 중 작은 쪽 = demo-rp(0x…C0DE)).</li>
     *   <li>POST /admin/api/active-tenant {second-rp} → 200, activeTenantId = second-rp.</li>
     *   <li>GET /admin/api/tenants → second-rp 의 slug 만 반환 (@Filter 격리).</li>
     *   <li>POST /admin/api/active-tenant {randomUnmapped} → 403.</li>
     *   <li>GET /admin/api/tenants/{demo-rp-id} — second-rp 활성 중에 demo-rp 직접 접근 → 403.</li>
     * </ol>
     */
    @Test
    void multiTenantOperatorCanSwitchAndIsIsolated() throws Exception {
        // ── 전제: demo-rp < second-rp (UUID 자연 정렬) ───────────────────────
        // TreeSet<UUID>.first() 는 자연 순서상 가장 작은 UUID 를 반환한다.
        // 0000…C0DE < 0000…C0DF 이므로 demo-rp 가 정렬 첫 번째.
        TreeSet<UUID> sortedAllowed = new TreeSet<>();
        sortedAllowed.add(DEMO_RP_ID);
        sortedAllowed.add(SECOND_RP_ID);
        UUID expectedDefault = sortedAllowed.first(); // demo-rp (0x…C0DE)
        assertThat(expectedDefault)
                .as("전제: demo-rp 가 정렬 첫 번째여야 함")
                .isEqualTo(DEMO_RP_ID);

        // ── 1. bob 로그인 ─────────────────────────────────────────────────
        HttpHeaders bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw");

        // ── 2. GET /admin/api/active-tenant → allowedTenantIds 2개 ────────
        //    ActiveTenantController 는 ApiResponse 래퍼 없이 ActiveTenantView 를 직접 반환.
        ResponseEntity<JsonNode> getTenant = rest.exchange(
                url("/admin/api/active-tenant"), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(getTenant.getStatusCode().is2xxSuccessful())
                .as("GET active-tenant must succeed: %s", getTenant.getStatusCode())
                .isTrue();
        JsonNode view = getTenant.getBody();
        assertThat(view).isNotNull();

        List<String> allowedIds = StreamSupport
                .stream(view.get("allowedTenantIds").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertThat(allowedIds)
                .as("bob 의 allowedTenantIds 는 2개여야 함: %s", allowedIds)
                .hasSize(2);

        // ── 3. 초기 activeTenantId == demo-rp (정렬 첫 번째) ────────────
        String initialActive = view.get("activeTenantId").asText();
        assertThat(UUID.fromString(initialActive))
                .as("초기 activeTenantId 는 정렬 첫 번째 UUID (demo-rp) 여야 함")
                .isEqualTo(expectedDefault);

        // ── 4. POST active-tenant {second-rp} → 200, activeTenantId = second-rp ──
        String switchBody = "{\"tenantId\":\"" + SECOND_RP_ID + "\"}";
        ResponseEntity<JsonNode> switchRes = rest.exchange(
                url("/admin/api/active-tenant"), HttpMethod.POST,
                new HttpEntity<>(switchBody, bobAuth), JsonNode.class);
        assertThat(switchRes.getStatusCode().is2xxSuccessful())
                .as("POST active-tenant (second-rp) must succeed: %s body=%s",
                        switchRes.getStatusCode(), switchRes.getBody())
                .isTrue();
        JsonNode switchView = switchRes.getBody();
        assertThat(UUID.fromString(switchView.get("activeTenantId").asText()))
                .as("전환 후 activeTenantId 는 second-rp 여야 함")
                .isEqualTo(SECOND_RP_ID);

        // ── 5. @Filter 격리: GET /admin/api/tenants → second-rp 만 반환 ──
        //    TenantAdminService.list() 는 currentTenantScope() 을 통해
        //    활성 RP 의 테넌트만 반환한다.
        ResponseEntity<JsonNode> listRes = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(listRes.getStatusCode().is2xxSuccessful())
                .as("GET /tenants 는 성공해야 함: %s", listRes.getStatusCode())
                .isTrue();
        JsonNode listData = listRes.getBody().get("data");
        List<String> slugs = StreamSupport
                .stream(listData.spliterator(), false)
                .map(n -> n.get("slug").asText())
                .toList();
        assertThat(slugs)
                .as("second-rp 활성 중 tenant 목록은 second-rp 만 포함해야 함 — 실제: %s", slugs)
                .containsExactly("second-rp");
        assertThat(slugs)
                .as("second-rp 활성 중 demo-rp 는 목록에 없어야 함")
                .doesNotContain("demo-rp");

        // ── 6. POST active-tenant {unmapped UUID} → 403 ACCESS_DENIED ────
        UUID unmapped = UUID.randomUUID();
        String unmappedBody = "{\"tenantId\":\"" + unmapped + "\"}";
        assertThatThrownBy(() -> http.exchange(
                url("/admin/api/active-tenant"), HttpMethod.POST,
                new HttpEntity<>(unmappedBody, bobAuth), JsonNode.class))
                .as("허용 외 UUID 로 전환 시도 → 403")
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e)
                        .getStatusCode().value())
                        .isEqualTo(403));

        // ── 7. assertCanAccessTenant 경유 — bob 의 allowedTenantIds 밖 테넌트 직접 접근 → 403 ──
        //    GET /admin/api/tenants/{id} → TenantAdminService.get() → assertCanAccessTenant() 호출.
        //    TenantBoundary.assertCanAccessTenant 는 allowedTenantIds 범위로 검사한다(활성 RP 무관).
        //    alice 가 만든 external-rp 는 bob 의 allowedTenantIds 에 없으므로 → 403.
        //    alice (PLATFORM_OPERATOR) 로 로그인하여 외부 테넌트 생성
        HttpHeaders aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw");
        String externalTenantBody = """
                {"slug":"external-rp","displayName":"External RP","rpId":"external.localhost",
                 "rpName":"External RP",
                 "allowedOrigins":["http://localhost:9092"],
                 "acceptedFormats":["none"],
                 "requireUserVerification":true,
                 "mdsRequired":false,
                 "attestationConveyance":"NONE",
                 "webauthnTimeoutMs":60000}
                """;
        ResponseEntity<JsonNode> createExternal = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(externalTenantBody, aliceAuth), JsonNode.class);
        assertThat(createExternal.getStatusCode().is2xxSuccessful())
                .as("alice 가 외부 테넌트 생성 → 201: %s body=%s",
                        createExternal.getStatusCode(), createExternal.getBody())
                .isTrue();
        String externalTenantId = createExternal.getBody().get("data").get("id").asText();

        // bob 세션은 유지 — second-rp 가 여전히 활성 상태.
        // external-rp 는 bob 의 allowedTenantIds 에 없으므로 assertCanAccessTenant → 403.
        assertThatThrownBy(() -> http.exchange(
                url("/admin/api/tenants/" + externalTenantId), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class))
                .as("bob 이 허용 외 테넌트(external-rp) 직접 접근 → 403")
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e)
                        .getStatusCode().value())
                        .isEqualTo(403));
    }
}
