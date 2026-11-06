package com.crosscert.passkey.app.sdk;

import com.crosscert.passkey.app.fido2.Fido2TestAuthenticator;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import com.crosscert.passkey.sdk.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.sdk.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.sdk.dto.AuthenticationStartRequest;
import com.crosscert.passkey.sdk.dto.AuthenticationStartResponse;
import com.crosscert.passkey.sdk.dto.RegistrationFinishRequest;
import com.crosscert.passkey.sdk.dto.RegistrationFinishResponse;
import com.crosscert.passkey.sdk.dto.RegistrationStartRequest;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.data.client.Origin;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sdk-java {@link PasskeyClient} → passkey-app 전 구간 E2E (Boot 4 업그레이드 안전망).
 *
 * <p>sdk-java 는 업그레이드 후에도 Spring 6 / Jackson 2 로 남고 passkey-app 만 Boot 4 로 올라가므로,
 * 실제 SDK 가 새 서버의 응답을 그대로 파싱·검증할 수 있는지가 외부 RP 관점의 계약이다.
 * 등록 → (에뮬레이터) → 등록 완료 → 인증 → (에뮬레이터) → 인증 완료 → ID Token 3-인자 검증
 * (서명·exp + iss/aud). 기존 rp-app 의 {@code @Disabled} 스모크 IT 를 대체한다.
 *
 * <p>컨테이너·테넌트·API Key 준비는 {@code Fido2EndToEndIT} 와 같은 방식이다(테넌트 1개).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SdkClientEndToEndIT {

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

    @LocalServerPort
    int port;

    @Value("${passkey.id-token.issuer-base}")
    String issuerBase;

    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired RedisConnectionFactory redisFactory;

    private static HikariDataSource adminPool;
    private static JdbcTemplate adminJdbc;

    private static final String TENANT_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String TENANT_HEX = TENANT_ID.replace("-", "");
    private static final String PREFIX = "pk_dddddddd";
    private static final String SECRET = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDD4";
    private static final String USER_HANDLE = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[]{41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56});

    @BeforeEach
    void seed() {
        TenantContextHolder.clear();
        ensureAdminPool();
        wipe();
        adminJdbc.update(
                "INSERT INTO PSK_APP_OWNER.tenant "
                        + "(id, slug, display_name, status, rp_id, rp_name, "
                        + " require_user_verification, mds_required, created_at, updated_at) "
                        + "VALUES (HEXTORAW(?), 'sdk-e2e', 'SDK Tenant', 'active', 'localhost', "
                        + "        'SDK Tenant', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)",
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
        adminPool.setPoolName("sdk-e2e-it-admin-pool");
        adminJdbc = new JdbcTemplate(adminPool);
    }

    private String origin() {
        return "http://localhost:" + port;
    }

    @Test
    void registerAuthenticateAndVerifyIdToken_throughSdk() {
        PasskeyClient client = PasskeyClient.of(
                PasskeyClientConfig.defaults(URI.create(origin()), () -> PREFIX + SECRET));
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator(Origin.create(origin()), mapper);

        RegistrationStartResponse regStart = client.registrationStart(
                new RegistrationStartRequest(USER_HANDLE, "SDK User", "sdk-user"));
        RegistrationFinishResponse regFinish = client.registrationFinish(new RegistrationFinishRequest(
                regStart.registrationToken(),
                authn.register(regStart.publicKeyCredentialCreationOptions())));
        assertThat(regFinish.credentialId()).isNotBlank();

        AuthenticationStartResponse authStart = client.authenticationStart(
                new AuthenticationStartRequest(USER_HANDLE));
        AuthenticationFinishResponse authFinish = client.authenticationFinish(new AuthenticationFinishRequest(
                authStart.authenticationToken(),
                authn.authenticate(authStart.publicKeyCredentialRequestOptions())));
        assertThat(authFinish.idToken()).isNotBlank();

        // rp-app WebAuthnController 와 같은 규칙: expectedIssuer = issuerBase + "/" + tenantId.
        IdTokenClaims claims = client.verifyIdToken(
                authFinish.idToken(), issuerBase + "/" + TENANT_ID, TENANT_ID);
        assertThat(claims.sub()).isEqualTo(USER_HANDLE);
        assertThat(claims.aud()).isEqualTo(TENANT_ID);
        assertThat(claims.amr()).containsExactly("webauthn");
    }
}
