package com.crosscert.passkey.app.sdk

import com.crosscert.passkey.app.fido2.Fido2TestAuthenticator
import com.crosscert.passkey.app.support.PasskeyAppItSupport
import com.crosscert.passkey.app.support.PasskeyAppItSupport.TenantSeeder
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.crosscert.passkey.sdk.PasskeyClient
import com.crosscert.passkey.sdk.PasskeyClientConfig
import com.crosscert.passkey.sdk.dto.AuthenticationFinishRequest
import com.crosscert.passkey.sdk.dto.AuthenticationStartRequest
import com.crosscert.passkey.sdk.dto.RegistrationFinishRequest
import com.crosscert.passkey.sdk.dto.RegistrationStartRequest
import com.crosscert.passkey.sdk.exception.PasskeyAuthException
import com.fasterxml.jackson.core.JsonProcessingException
import com.webauthn4j.data.client.Origin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.Base64
import com.fasterxml.jackson.databind.JsonNode as SdkJsonNode
import com.fasterxml.jackson.databind.ObjectMapper as SdkObjectMapper
import tools.jackson.databind.JsonNode as ServerJsonNode

private const val TENANT_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
private val TENANT_HEX = TENANT_ID.replace("-", "")
private const val PREFIX = "pk_dddddddd"
private const val SECRET = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDD4"
private val USER_HANDLE: String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(byteArrayOf(41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56))

private val SDK_JSON = SdkObjectMapper()

/**
 * sdk-java [PasskeyClient] → passkey-app 전 구간 E2E (Boot 4 업그레이드 안전망).
 *
 * sdk-java 는 업그레이드 후에도 Spring 6 / Jackson 2 로 남고 passkey-app 만 Boot 4 로 올라가므로,
 * 실제 SDK 가 새 서버의 응답을 그대로 파싱·검증할 수 있는지가 외부 RP 관점의 계약이다.
 * 등록 → (에뮬레이터) → 등록 완료 → 인증 → (에뮬레이터) → 인증 완료 → ID Token 3-인자 검증
 * (서명·exp + iss/aud). 기존 rp-app 의 `@Disabled` 스모크 IT 를 대체한다.
 *
 * 컨테이너·테넌트·API Key 준비는 [PasskeyAppItSupport](`Fido2EndToEndIT` 와 같은 절차, 테넌트 1개).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SdkClientEndToEndIT @Autowired constructor(
    @LocalServerPort private val port: Int,
    @Value("\${passkey.id-token.issuer-base}") private val issuerBase: String,
    private val encoder: PasswordEncoder,
    private val mapper: ObjectMapper,
    private val redisFactory: RedisConnectionFactory,
) {

    companion object {
        @Container
        @JvmField
        val ORACLE: OracleContainer = PasskeyAppItSupport.oracleContainer()

        @Container
        @JvmField
        val REDIS: GenericContainer<*> = PasskeyAppItSupport.redisContainer()

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(reg: DynamicPropertyRegistry) {
            PasskeyAppItSupport.bootstrapAndRegister(reg, ORACLE, REDIS)
        }

        // 클래스 단위로 1회 생성(첫 @BeforeEach — 컨테이너 기동 후)하고 @AfterAll 에서 닫는다.
        private var seeder: TenantSeeder? = null

        @JvmStatic
        @AfterAll
        fun closeSeeder() {
            seeder?.close()
            seeder = null
        }
    }

    @BeforeEach
    fun seed() {
        TenantContextHolder.clear()
        val s = seeder ?: TenantSeeder(ORACLE, "sdk-e2e-it-admin-pool").also { seeder = it }
        s.wipe()
        s.seedTenant(TENANT_HEX, "sdk-e2e", "SDK Tenant", origin(), PREFIX, encoder.encode(SECRET))
    }

    @AfterEach
    fun cleanup() {
        TenantContextHolder.clear()
        seeder?.wipe()
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    private fun origin() = "http://localhost:$port"

    /**
     * sdk-java 공개 API 는 Jackson 2 `JsonNode` 를 쓰고(외부 RP 호환 — 프로젝트 제약), 서버 측
     * 테스트 authenticator 는 Jackson 3 트리를 쓴다. 두 트리는 JSON 문자열로만 건넌다.
     */
    private fun fromSdk(sdkNode: SdkJsonNode): ServerJsonNode = mapper.readTree(sdkNode.toString())

    private fun toSdk(node: ServerJsonNode): SdkJsonNode =
        try {
            SDK_JSON.readTree(node.toString())
        } catch (e: JsonProcessingException) {
            throw IllegalStateException(e)
        }

    @Test
    fun registerAuthenticateAndVerifyIdToken_throughSdk() {
        val client = PasskeyClient.of(PasskeyClientConfig.defaults(URI.create(origin())) { PREFIX + SECRET })
        val authn = Fido2TestAuthenticator(Origin.create(origin()), mapper)

        val regStart = client.registrationStart(RegistrationStartRequest(USER_HANDLE, "SDK User", "sdk-user"))
        val regFinish = client.registrationFinish(
            RegistrationFinishRequest(
                regStart.registrationToken(),
                toSdk(authn.register(fromSdk(regStart.publicKeyCredentialCreationOptions()))),
            ),
        )
        assertThat(regFinish.credentialId()).isNotBlank()

        val authStart = client.authenticationStart(AuthenticationStartRequest(USER_HANDLE))
        val authFinish = client.authenticationFinish(
            AuthenticationFinishRequest(
                authStart.authenticationToken(),
                toSdk(authn.authenticate(fromSdk(authStart.publicKeyCredentialRequestOptions()))),
            ),
        )
        assertThat(authFinish.idToken()).isNotBlank()

        // rp-app WebAuthnController 와 같은 규칙: expectedIssuer = issuerBase + "/" + tenantId.
        val claims = client.verifyIdToken(authFinish.idToken(), "$issuerBase/$TENANT_ID", TENANT_ID)
        assertThat(claims.sub()).isEqualTo(USER_HANDLE)
        assertThat(claims.aud()).isEqualTo(TENANT_ID)
        assertThat(claims.amr()).containsExactly("webauthn")
    }

    /**
     * 에러 경로: 잘못된 API Key → passkey-app 401 → SDK [PasskeyAuthException].
     * 이 서버(Spring 7)와 같은 클래스패스에서 SDK 의 에러 핸들러(3-인자 handleError)가 실제로
     * 호출되는지 확인한다 — Spring 7 은 1-인자 handleError 를 호출하지 않는다.
     */
    @Test
    fun wrongApiKey_throwsPasskeyAuthException_throughSdk() {
        val client = PasskeyClient.of(
            PasskeyClientConfig.defaults(URI.create(origin())) { PREFIX + "WRONG-SECRET-xxxxxxxxxxxxxxxx" },
        )

        assertThatThrownBy { client.registrationStart(RegistrationStartRequest(USER_HANDLE, "SDK User", "sdk-user")) }
            .isInstanceOf(PasskeyAuthException::class.java)
            .satisfies(ThrowingConsumer { assertThat((it as PasskeyAuthException).httpStatus).isEqualTo(401) })
    }
}
