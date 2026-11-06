package com.crosscert.passkey.app.contract;

import com.crosscert.passkey.app.fido2.Fido2TestAuthenticator;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webauthn4j.data.client.Origin;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * passkey-app 외부 API 의 JSON 계약 스냅샷 (Boot 4 업그레이드 안전망).
 *
 * <p>각 응답 바디를 {@link JsonShape} 로 정규화해 {@code src/test/resources/contract/<케이스>.shape.json}
 * 과 비교한다 — 필드명·필드 존재(null 생략 포함)·값 타입·날짜 오프셋·에러 봉투가 계약이다.
 * 스냅샷은 업그레이드 전(Boot 3.5)에 기록했고 이후 수정하지 않는다(수정 = 계약 변경).
 *
 * <p>기록: {@code ./gradlew :passkey-app:test --tests '*ApiContractIT' -Pcontract.record=true}
 * — 파일을 쓰고 테스트는 실패한다(기록 후 내용을 눈으로 검토). 기본 모드는 비교만 하며
 * 스냅샷이 없거나 다르면 실패한다.
 *
 * <p>컨테이너·테넌트·API Key 준비는 {@code Fido2EndToEndIT} 와 같은 방식이다(테넌트 1개).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ApiContractIT {

    private static final boolean RECORD = Boolean.getBoolean("contract.record");
    private static final Path SNAPSHOT_DIR = Path.of("src/test/resources/contract");

    // ------------------------------------------------------------
    // Containers (Fido2EndToEndIT 와 동일)
    // ------------------------------------------------------------

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("PSK_APP_OWNER")
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
        reg.add("spring.datasource.username", () -> "PSK_APP_RUNTIME_USER");
        reg.add("spring.datasource.password", () -> "runtime_pw");
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // ------------------------------------------------------------
    // Wiring / seed
    // ------------------------------------------------------------

    @LocalServerPort
    int port;

    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired RedisConnectionFactory redisFactory;

    private static HikariDataSource adminPool;
    private static JdbcTemplate adminJdbc;

    private static final String TENANT_HEX = "cccccccccccccccccccccccccccccccc";
    private static final String PREFIX = "pk_cccccccc";
    private static final String SECRET = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCC3";
    private static final String USER_HANDLE = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[]{21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36});

    private RestTemplate http;

    @BeforeEach
    void seed() {
        TenantContextHolder.clear();
        ensureAdminPool();
        wipe();
        adminJdbc.update(
                "INSERT INTO PSK_APP_OWNER.tenant "
                        + "(id, slug, display_name, status, rp_id, rp_name, "
                        + " require_user_verification, mds_required, created_at, updated_at) "
                        + "VALUES (HEXTORAW(?), 'contract', 'Contract Tenant', 'active', 'localhost', "
                        + "        'Contract Tenant', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)",
                TENANT_HEX);
        adminJdbc.update(
                "INSERT INTO PSK_APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order) "
                        + "VALUES (SYS_GUID(), HEXTORAW(?), ?, 0)",
                TENANT_HEX, origin());
        for (String fmt : new String[]{"none", "packed"}) {
            adminJdbc.update(
                    "INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format) "
                            + "VALUES (SYS_GUID(), HEXTORAW(?), ?)",
                    TENANT_HEX, fmt);
        }
        adminJdbc.update(
                "INSERT INTO PSK_APP_OWNER.api_key (id, tenant_id, key_prefix, key_hash, name, created_at) "
                        + "VALUES (SYS_GUID(), HEXTORAW(?), ?, ?, 'primary', SYSTIMESTAMP)",
                TENANT_HEX, PREFIX, encoder.encode(SECRET));
        for (String scope : new String[]{"registration", "authentication"}) {
            adminJdbc.update(
                    "INSERT INTO PSK_APP_OWNER.api_key_scope (id, api_key_id, scope) "
                            + "SELECT SYS_GUID(), id, ? FROM PSK_APP_OWNER.api_key WHERE key_prefix = ?",
                    scope, PREFIX);
        }

        // JDK HttpClient 기반 — 기본 HttpURLConnection 은 POST 401 응답의 바디를 버린다
        // (streaming mode 에서 getErrorStream()=null). 에러 봉투 형태를 보려면 바디가 필요하다.
        http = new RestTemplate(new JdkClientHttpRequestFactory());
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse res) {
                return false; // 4xx/5xx 도 그대로 받아 상태·바디를 단언한다.
            }
        });
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        if (adminJdbc != null) wipe();
        redisFactory.getConnection().serverCommands().flushAll();
    }

    @AfterAll
    static void closeAdminPool() {
        if (adminPool != null) {
            adminPool.close();
            adminPool = null;
            adminJdbc = null;
        }
    }

    private static void wipe() {
        adminJdbc.update("DELETE FROM PSK_APP_OWNER.credential");
        adminJdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope");
        adminJdbc.update("DELETE FROM PSK_APP_OWNER.api_key");
        adminJdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin");
        adminJdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format");
        adminJdbc.update("DELETE FROM PSK_APP_OWNER.ceremony_event");
        adminJdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant");
        adminJdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'");
        adminJdbc.update("DELETE FROM PSK_APP_OWNER.tenant");
    }

    private void ensureAdminPool() {
        if (adminPool != null) return;
        adminPool = new HikariDataSource();
        adminPool.setJdbcUrl(ORACLE.getJdbcUrl());
        adminPool.setUsername("PSK_APP_ADMIN_USER");
        adminPool.setPassword("admin_pw");
        adminPool.setMaximumPoolSize(2);
        adminPool.setMinimumIdle(1);
        adminPool.setPoolName("contract-it-admin-pool");
        adminJdbc = new JdbcTemplate(adminPool);
    }

    private String origin() {
        return "http://localhost:" + port;
    }

    // ------------------------------------------------------------
    // Happy-path shapes
    // ------------------------------------------------------------

    @Test
    void registrationStart_shape() throws Exception {
        ResponseEntity<String> res = post("/api/v1/rp/registration/start", registrationStartBody());
        assertOkJson(res);
        assertMatchesSnapshot("registration-start", res.getBody());
    }

    @Test
    void registrationFinish_shape() throws Exception {
        Ceremony c = new Ceremony();
        ResponseEntity<String> res = c.registerFinishRaw();
        assertOkJson(res);
        assertMatchesSnapshot("registration-finish", res.getBody());
    }

    @Test
    void authenticationStart_shape() throws Exception {
        Ceremony c = new Ceremony();
        c.register();
        ResponseEntity<String> res = c.authenticationStartRaw();
        assertOkJson(res);
        assertMatchesSnapshot("authentication-start", res.getBody());
    }

    @Test
    void authenticationFinish_shape() throws Exception {
        Ceremony c = new Ceremony();
        c.register();
        ResponseEntity<String> res = c.authenticationFinishRaw();
        assertOkJson(res);
        assertMatchesSnapshot("authentication-finish", res.getBody());
    }

    @Test
    void idToken_claimShape() throws Exception {
        Ceremony c = new Ceremony();
        c.register();
        String idToken = data(c.authenticationFinishRaw()).get("idToken").asText();

        String[] parts = idToken.split("\\.");
        assertThat(parts).hasSize(3);
        ObjectNode decoded = mapper.createObjectNode();
        decoded.set("header", mapper.readTree(Base64.getUrlDecoder().decode(parts[0])));
        decoded.set("payload", mapper.readTree(Base64.getUrlDecoder().decode(parts[1])));
        assertMatchesSnapshot("id-token-claims", mapper.writeValueAsString(decoded));
    }

    @Test
    void credentials_shape() throws Exception {
        // register → authenticate(lastUsedAt 채움) → label 지정 → 목록. CredentialView 의 4개 필드가
        // 모두 non-null 이 되도록 만들어 null 생략 여부와 무관하게 전체 형태를 고정한다.
        Ceremony c = new Ceremony();
        c.register();
        c.authenticationFinishRaw();
        JsonNode listed = data(get("/api/v1/rp/credentials?userHandle=" + USER_HANDLE));
        String credentialId = listed.get(0).get("credentialId").asText();
        ResponseEntity<String> renamed = post("/api/v1/rp/credentials/" + credentialId + "/label",
                "{\"userHandle\":\"" + USER_HANDLE + "\",\"label\":\"contract key\"}");
        assertOkJson(renamed);

        ResponseEntity<String> res = get("/api/v1/rp/credentials?userHandle=" + USER_HANDLE);
        assertOkJson(res);
        assertMatchesSnapshot("credentials-list", res.getBody());
    }

    @Test
    void jwks_shape() throws Exception {
        ResponseEntity<String> res = http.exchange(url("/.well-known/jwks.json"),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertOkJson(res);
        assertMatchesSnapshot("jwks", res.getBody());
    }

    // ------------------------------------------------------------
    // Error envelopes
    // ------------------------------------------------------------

    @Test
    void missingApiKey_errorShape() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = http.exchange(url("/api/v1/rp/registration/start"),
                HttpMethod.POST, new HttpEntity<>(registrationStartBody(), h), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertMatchesSnapshot("error-missing-api-key", res.getBody());
    }

    @Test
    void validationError_errorShape() throws Exception {
        ResponseEntity<String> res = post("/api/v1/rp/registration/start",
                "{\"userHandle\":\"\",\"displayName\":\"\",\"username\":\"\"}");
        assertErrorEnvelope(res, 400, "C001");
        assertMatchesSnapshot("error-validation", res.getBody());
    }

    @Test
    void unknownToken_errorShape() throws Exception {
        ResponseEntity<String> res = post("/api/v1/rp/registration/finish",
                "{\"registrationToken\":\"no-such-token\",\"publicKeyCredential\":{}}");
        assertErrorEnvelope(res, 400, "C001");
        assertMatchesSnapshot("error-unknown-token", res.getBody());
    }

    @Test
    void unknownRequestField_isRejected() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(registrationStartBody());
        body.put("bogus", 1);
        ResponseEntity<String> res = post("/api/v1/rp/registration/start", mapper.writeValueAsString(body));
        assertErrorEnvelope(res, 400, "C001");
        assertMatchesSnapshot("error-unknown-field", res.getBody());
    }

    @Test
    void overDeepJson_isRejected() throws Exception {
        // 바깥 객체(깊이 1) + 배열 64겹 = 깊이 65 → StreamReadConstraints(maxNestingDepth=64) 초과.
        ResponseEntity<String> tooDeep = post("/api/v1/rp/registration/finish", nestedFinishBody(64));
        assertErrorEnvelope(tooDeep, 400, "C001");
        assertThat(mapper.readTree(tooDeep.getBody()).get("message").asText())
                .isEqualTo("Malformed JSON request");

        // 대조군: 깊이 64 는 파서를 통과해 토큰 검증 단계에서 거부된다(파서 거부가 아님을 확인).
        ResponseEntity<String> atLimit = post("/api/v1/rp/registration/finish", nestedFinishBody(63));
        assertErrorEnvelope(atLimit, 400, "C001");
        assertThat(mapper.readTree(atLimit.getBody()).get("message").asText())
                .isNotEqualTo("Malformed JSON request");
    }

    // ------------------------------------------------------------
    // Ceremony helper (등록 → 인증, 한 authenticator 인스턴스 공유)
    // ------------------------------------------------------------

    private final class Ceremony {
        private final Fido2TestAuthenticator authn = new Fido2TestAuthenticator(Origin.create(origin()), mapper);

        ResponseEntity<String> registerFinishRaw() throws Exception {
            JsonNode start = data(post("/api/v1/rp/registration/start", registrationStartBody()));
            ObjectNode body = mapper.createObjectNode();
            body.put("registrationToken", start.get("registrationToken").asText());
            body.set("publicKeyCredential", authn.register(start.get("publicKeyCredentialCreationOptions")));
            return post("/api/v1/rp/registration/finish", mapper.writeValueAsString(body));
        }

        void register() throws Exception {
            data(registerFinishRaw());
        }

        ResponseEntity<String> authenticationStartRaw() {
            return post("/api/v1/rp/authentication/start", "{\"userHandle\":\"" + USER_HANDLE + "\"}");
        }

        ResponseEntity<String> authenticationFinishRaw() throws Exception {
            JsonNode start = data(authenticationStartRaw());
            ObjectNode body = mapper.createObjectNode();
            body.put("authenticationToken", start.get("authenticationToken").asText());
            body.set("publicKeyCredential", authn.authenticate(start.get("publicKeyCredentialRequestOptions")));
            return post("/api/v1/rp/authentication/finish", mapper.writeValueAsString(body));
        }
    }

    // ------------------------------------------------------------
    // HTTP / assertion helpers
    // ------------------------------------------------------------

    private String registrationStartBody() {
        return "{\"userHandle\":\"" + USER_HANDLE + "\",\"displayName\":\"Contract User\","
                + "\"username\":\"contract-user\"}";
    }

    private static String nestedFinishBody(int arrayDepth) {
        return "{\"registrationToken\":\"no-such-token\",\"publicKeyCredential\":"
                + "[".repeat(arrayDepth) + "]".repeat(arrayDepth) + "}";
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", PREFIX + SECRET);
        return h;
    }

    private ResponseEntity<String> post(String path, String body) {
        return http.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, authHeaders()), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
    }

    private JsonNode data(ResponseEntity<String> res) throws IOException {
        assertOkJson(res);
        JsonNode tree = mapper.readTree(res.getBody());
        assertThat(tree.path("success").asBoolean()).as("success flag: %s", res.getBody()).isTrue();
        return tree.path("data");
    }

    private static void assertOkJson(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).as("status, body=%s", res.getBody()).isEqualTo(200);
        assertThat(res.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
    }

    private void assertErrorEnvelope(ResponseEntity<String> res, int status, String code) throws IOException {
        assertThat(res.getStatusCode().value()).as("status, body=%s", res.getBody()).isEqualTo(status);
        JsonNode tree = mapper.readTree(res.getBody());
        assertThat(tree.path("success").asBoolean()).isFalse();
        assertThat(tree.path("code").asText()).isEqualTo(code);
        assertThat(tree.path("error").path("errorCode").asText()).isEqualTo(code);
    }

    /**
     * 응답 바디의 형태를 스냅샷과 비교한다. 비교는 파싱된 트리 동등성(포매팅 무관)이다.
     * {@code contract.record=true} 이면 스냅샷을 쓰고 실패한다.
     */
    private void assertMatchesSnapshot(String name, String body) throws IOException {
        String actual = JsonShape.of(body);
        String file = name + ".shape.json";
        if (RECORD) {
            Files.createDirectories(SNAPSHOT_DIR);
            Files.writeString(SNAPSHOT_DIR.resolve(file), actual, StandardCharsets.UTF_8);
            fail("recorded snapshot " + SNAPSHOT_DIR.resolve(file)
                    + " — review it, then re-run without -Pcontract.record=true");
        }
        String expected;
        try (InputStream in = ApiContractIT.class.getResourceAsStream("/contract/" + file)) {
            if (in == null) {
                fail("missing snapshot contract/" + file + " — record it with -Pcontract.record=true");
            }
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(mapper.readTree(actual))
                .as("contract shape '%s' changed.%nexpected:%n%s%nactual:%n%s", name, expected, actual)
                .isEqualTo(mapper.readTree(expected));
    }
}
