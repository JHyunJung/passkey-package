package com.crosscert.passkey.app.sdk;

import com.crosscert.passkey.app.fido2.Fido2TestAuthenticator;
import com.crosscert.passkey.app.support.PasskeyAppItSupport;
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
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.data.client.Origin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * sdk-java {@link PasskeyClient} → passkey-app 전 구간 E2E (Boot 4 업그레이드 안전망).
 *
 * <p>sdk-java 는 업그레이드 후에도 Spring 6 / Jackson 2 로 남고 passkey-app 만 Boot 4 로 올라가므로,
 * 실제 SDK 가 새 서버의 응답을 그대로 파싱·검증할 수 있는지가 외부 RP 관점의 계약이다.
 * 등록 → (에뮬레이터) → 등록 완료 → 인증 → (에뮬레이터) → 인증 완료 → ID Token 3-인자 검증
 * (서명·exp + iss/aud). 기존 rp-app 의 {@code @Disabled} 스모크 IT 를 대체한다.
 *
 * <p>컨테이너·테넌트·API Key 준비는 {@link PasskeyAppItSupport}({@code Fido2EndToEndIT} 와 같은 절차, 테넌트 1개).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SdkClientEndToEndIT {

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

    @Value("${passkey.id-token.issuer-base}")
    String issuerBase;

    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired RedisConnectionFactory redisFactory;

    private static PasskeyAppItSupport.TenantSeeder seeder;

    private static final String TENANT_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String TENANT_HEX = TENANT_ID.replace("-", "");
    private static final String PREFIX = "pk_dddddddd";
    private static final String SECRET = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDD4";
    private static final String USER_HANDLE = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[]{41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56});

    @BeforeEach
    void seed() {
        TenantContextHolder.clear();
        if (seeder == null) seeder = new PasskeyAppItSupport.TenantSeeder(ORACLE, "sdk-e2e-it-admin-pool");
        seeder.wipe();
        seeder.seedTenant(TENANT_HEX, "sdk-e2e", "SDK Tenant", origin(), PREFIX, encoder.encode(SECRET));
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

    /**
     * 에러 경로: 잘못된 API Key → passkey-app 401 → SDK {@link PasskeyAuthException}.
     * 이 서버(Spring 7)와 같은 클래스패스에서 SDK 의 에러 핸들러(3-인자 handleError)가 실제로
     * 호출되는지 확인한다 — Spring 7 은 1-인자 handleError 를 호출하지 않는다.
     */
    @Test
    void wrongApiKey_throwsPasskeyAuthException_throughSdk() {
        PasskeyClient client = PasskeyClient.of(
                PasskeyClientConfig.defaults(URI.create(origin()), () -> PREFIX + "WRONG-SECRET-xxxxxxxxxxxxxxxx"));

        assertThatThrownBy(() -> client.registrationStart(
                new RegistrationStartRequest(USER_HANDLE, "SDK User", "sdk-user")))
                .isInstanceOf(PasskeyAuthException.class)
                .satisfies(e -> assertThat(((PasskeyAuthException) e).getHttpStatus()).isEqualTo(401));
    }
}
