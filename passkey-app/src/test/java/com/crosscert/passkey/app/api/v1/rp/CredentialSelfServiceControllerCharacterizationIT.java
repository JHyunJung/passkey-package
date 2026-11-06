package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.app.fido2.Fido2TestAuthenticator;
import com.crosscert.passkey.app.support.PasskeyAppItSupport;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import com.webauthn4j.data.client.Origin;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialSelfServiceController} 의 HTTP 레벨 분기 특성 테스트 (passkey-app Kotlin 전환 안전망).
 *
 * <p>ApiContractIT·RequestDtoMissingFieldContractIT 가 덮지 않는 외부 분기를 고정한다:
 * {@code @Validated} 메서드 파라미터 검증(@RequestParam @NotBlank → ConstraintViolationException 의
 * propertyPath 는 메서드명·파라미터명을 담는다), 필수 파라미터 누락, base64url 디코드 실패,
 * 존재하지 않는 credential, DELETE 성공 경로.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class CredentialSelfServiceControllerCharacterizationIT {

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = PasskeyAppItSupport.oracleContainer();

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = PasskeyAppItSupport.redisContainer();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        PasskeyAppItSupport.bootstrapAndRegister(reg, ORACLE, REDIS);
    }

    @LocalServerPort
    int port;

    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired RedisConnectionFactory redisFactory;

    private static PasskeyAppItSupport.TenantSeeder seeder;

    private static final String TENANT_HEX = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String PREFIX = "pk_eeeeeeee";
    private static final String SECRET = "EEEEEEEEEEEEEEEEEEEEEEEEEEEEE5";
    private static final String USER_HANDLE = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76});
    /** 형식은 올바르지만 존재하지 않는 credentialId. */
    private static final String UNKNOWN_CREDENTIAL_ID = "AAAAAAAAAAAAAAAAAAAAAA";
    /** base64url 알파벳 밖의 문자 — Base64.getUrlDecoder() 가 IllegalArgumentException. */
    private static final String NOT_BASE64URL = "%2B%2B%2B%2B";

    private RestTemplate http;

    @BeforeEach
    void seed() {
        TenantContextHolder.clear();
        if (seeder == null) seeder = new PasskeyAppItSupport.TenantSeeder(ORACLE, "cred-ctrl-it-admin-pool");
        seeder.wipe();
        seeder.seedTenant(TENANT_HEX, "cred-ctrl", "Credential Controller Tenant", origin(), PREFIX,
                encoder.encode(SECRET));
        http = new RestTemplate(new JdkClientHttpRequestFactory());
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse res) {
                return false;
            }
        });
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        if (seeder != null) seeder.wipe();
        redisFactory.getConnection().serverCommands().flushAll();
    }

    @AfterAll
    static void closeSeeder() {
        if (seeder != null) {
            seeder.close();
            seeder = null;
        }
    }

    // ------------------------------------------------------------
    // GET /api/v1/rp/credentials
    // ------------------------------------------------------------

    @Test
    void list_unknownUser_returnsEmptyArray() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=" + USER_HANDLE, null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode tree = mapper.readTree(res.getBody());
        assertThat(tree.path("success").asBoolean()).isTrue();
        assertThat(tree.path("data").isArray()).isTrue();
        assertThat(tree.path("data")).isEmpty();
    }

    @Test
    void list_missingUserHandle_isMissingParameter() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.GET, "/api/v1/rp/credentials", null);
        assertError(res, 400, "C004",
                "Required request parameter 'userHandle' for method parameter type String is not present");
    }

    @Test
    void list_blankUserHandle_isConstraintViolationWithMethodPath() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=", null);
        assertError(res, 400, "C001", "Invalid input value");
        assertThat(fieldErrorFields(res)).containsExactly("list.userHandle");
    }

    @Test
    void list_notBase64Url_isInvalidInput() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=" + NOT_BASE64URL, null);
        assertError(res, 400, "C001", "base64url 형식이 아님");
    }

    // ------------------------------------------------------------
    // POST /api/v1/rp/credentials/{credentialId}/label
    // ------------------------------------------------------------

    @Test
    void rename_unknownCredential_isEntityNotFound() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.POST,
                "/api/v1/rp/credentials/" + UNKNOWN_CREDENTIAL_ID + "/label", renameBody(USER_HANDLE));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(mapper.readTree(res.getBody()).path("code").asString()).isEqualTo("C003");
    }

    @Test
    void rename_notBase64UrlCredentialId_isInvalidInput() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.POST,
                "/api/v1/rp/credentials/" + NOT_BASE64URL + "/label", renameBody(USER_HANDLE));
        assertError(res, 400, "C001", "base64url 형식이 아님");
    }

    @Test
    void rename_notBase64UrlUserHandle_isInvalidInput() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.POST,
                "/api/v1/rp/credentials/" + UNKNOWN_CREDENTIAL_ID + "/label", renameBody("+++"));
        assertError(res, 400, "C001", "base64url 형식이 아님");
    }

    // ------------------------------------------------------------
    // DELETE /api/v1/rp/credentials/{credentialId}
    // ------------------------------------------------------------

    @Test
    void delete_missingUserHandle_isMissingParameter() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.DELETE, "/api/v1/rp/credentials/" + UNKNOWN_CREDENTIAL_ID, null);
        assertError(res, 400, "C004",
                "Required request parameter 'userHandle' for method parameter type String is not present");
    }

    @Test
    void delete_blankUserHandle_isConstraintViolationWithMethodPath() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.DELETE,
                "/api/v1/rp/credentials/" + UNKNOWN_CREDENTIAL_ID + "?userHandle=", null);
        assertError(res, 400, "C001", "Invalid input value");
        assertThat(fieldErrorFields(res)).containsExactly("delete.userHandle");
    }

    @Test
    void delete_unknownCredential_isEntityNotFound() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.DELETE,
                "/api/v1/rp/credentials/" + UNKNOWN_CREDENTIAL_ID + "?userHandle=" + USER_HANDLE, null);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(mapper.readTree(res.getBody()).path("code").asString()).isEqualTo("C003");
    }

    @Test
    void delete_notBase64UrlCredentialId_isInvalidInput() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.DELETE,
                "/api/v1/rp/credentials/" + NOT_BASE64URL + "?userHandle=" + USER_HANDLE, null);
        assertError(res, 400, "C001", "base64url 형식이 아님");
    }

    @Test
    void delete_registeredCredential_returnsOkEnvelopeAndRemovesIt() throws Exception {
        register();
        JsonNode listed = data(exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=" + USER_HANDLE, null));
        assertThat(listed).hasSize(1);
        String credentialId = listed.get(0).get("credentialId").asString();

        ResponseEntity<String> res = exchange(HttpMethod.DELETE,
                "/api/v1/rp/credentials/" + credentialId + "?userHandle=" + USER_HANDLE, null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getContentType()).isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
        JsonNode tree = mapper.readTree(res.getBody());
        assertThat(tree.path("success").asBoolean()).isTrue();
        assertThat(tree.has("data")).as("void ok() omits data: %s", res.getBody()).isFalse();

        assertThat(data(exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=" + USER_HANDLE, null))).isEmpty();
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------

    private void register() throws Exception {
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator(Origin.create(origin()), mapper);
        JsonNode start = data(exchange(HttpMethod.POST, "/api/v1/rp/registration/start",
                "{\"userHandle\":\"" + USER_HANDLE + "\",\"displayName\":\"Cred User\",\"username\":\"cred-user\"}"));
        ObjectNode body = mapper.createObjectNode();
        body.put("registrationToken", start.get("registrationToken").asString());
        body.set("publicKeyCredential", authn.register(start.get("publicKeyCredentialCreationOptions")));
        data(exchange(HttpMethod.POST, "/api/v1/rp/registration/finish", mapper.writeValueAsString(body)));
    }

    private static String renameBody(String userHandle) {
        return "{\"userHandle\":\"" + userHandle + "\",\"label\":\"k\"}";
    }

    private String origin() {
        return "http://localhost:" + port;
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", PREFIX + SECRET);
        // 쿼리의 %XX 는 이미 인코딩된 값 — URI 로 넘겨 RestTemplate 이 다시 인코딩하지 않게 한다.
        return http.exchange(java.net.URI.create("http://localhost:" + port + path), method,
                new HttpEntity<>(body, h), String.class);
    }

    private JsonNode data(ResponseEntity<String> res) throws Exception {
        assertThat(res.getStatusCode().value()).as("status, body=%s", res.getBody()).isEqualTo(200);
        JsonNode tree = mapper.readTree(res.getBody());
        assertThat(tree.path("success").asBoolean()).as("success flag: %s", res.getBody()).isTrue();
        return tree.path("data");
    }

    private void assertError(ResponseEntity<String> res, int status, String code, String message) throws Exception {
        assertThat(res.getStatusCode().value()).as("status, body=%s", res.getBody()).isEqualTo(status);
        JsonNode tree = mapper.readTree(res.getBody());
        assertThat(tree.path("success").asBoolean()).isFalse();
        assertThat(tree.path("code").asString()).isEqualTo(code);
        assertThat(tree.path("message").asString()).as("body=%s", res.getBody()).isEqualTo(message);
    }

    private List<String> fieldErrorFields(ResponseEntity<String> res) throws Exception {
        List<String> fields = new ArrayList<>();
        for (JsonNode fe : mapper.readTree(res.getBody()).path("error").path("fieldErrors")) {
            fields.add(fe.path("field").asString());
        }
        return fields;
    }
}
