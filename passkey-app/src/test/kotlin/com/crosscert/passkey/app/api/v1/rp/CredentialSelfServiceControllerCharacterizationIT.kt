package com.crosscert.passkey.app.api.v1.rp

import com.crosscert.passkey.app.fido2.Fido2TestAuthenticator
import com.crosscert.passkey.app.support.PasskeyAppItSupport
import com.crosscert.passkey.app.support.PasskeyAppItSupport.TenantSeeder
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.webauthn4j.data.client.Origin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.Base64

private const val TENANT_HEX = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
private const val PREFIX = "pk_eeeeeeee"
private const val SECRET = "EEEEEEEEEEEEEEEEEEEEEEEEEEEEE5"
private val USER_HANDLE: String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(byteArrayOf(61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76))

/** 형식은 올바르지만 존재하지 않는 credentialId. */
private const val UNKNOWN_CREDENTIAL_ID = "AAAAAAAAAAAAAAAAAAAAAA"

/** base64url 알파벳 밖의 문자 — Base64.getUrlDecoder() 가 IllegalArgumentException. */
private const val NOT_BASE64URL = "%2B%2B%2B%2B"

/**
 * [CredentialSelfServiceController] 의 HTTP 레벨 분기 특성 테스트 (passkey-app Kotlin 전환 안전망).
 *
 * ApiContractIT·RequestDtoMissingFieldContractIT 가 덮지 않는 외부 분기를 고정한다:
 * `@Validated` 메서드 파라미터 검증(@RequestParam @NotBlank → ConstraintViolationException 의
 * propertyPath 는 메서드명·파라미터명을 담는다), 필수 파라미터 누락, base64url 디코드 실패,
 * 존재하지 않는 credential, DELETE 성공 경로.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class CredentialSelfServiceControllerCharacterizationIT @Autowired constructor(
    @LocalServerPort private val port: Int,
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

    private val http = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(res: ClientHttpResponse) = false
        }
    }

    @BeforeEach
    fun seed() {
        TenantContextHolder.clear()
        val s = seeder ?: TenantSeeder(ORACLE, "cred-ctrl-it-admin-pool").also { seeder = it }
        s.wipe()
        s.seedTenant(
            TENANT_HEX, "cred-ctrl", "Credential Controller Tenant", origin(), PREFIX,
            encoder.encode(SECRET),
        )
    }

    @AfterEach
    fun cleanup() {
        TenantContextHolder.clear()
        seeder?.wipe()
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    // ------------------------------------------------------------
    // GET /api/v1/rp/credentials
    // ------------------------------------------------------------

    @Test
    fun list_unknownUser_returnsEmptyArray() {
        val res = exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=$USER_HANDLE", null)
        assertThat(res.statusCode.value()).isEqualTo(200)
        val tree = mapper.readTree(res.body)
        assertThat(tree.path("success").asBoolean()).isTrue()
        assertThat(tree.path("data").isArray).isTrue()
        assertThat(tree.path("data")).isEmpty()
    }

    @Test
    fun list_missingUserHandle_isMissingParameter() {
        val res = exchange(HttpMethod.GET, "/api/v1/rp/credentials", null)
        assertError(
            res, 400, "C004",
            "Required request parameter 'userHandle' for method parameter type String is not present",
        )
    }

    @Test
    fun list_blankUserHandle_isConstraintViolationWithMethodPath() {
        val res = exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=", null)
        assertError(res, 400, "C001", "Invalid input value")
        assertThat(fieldErrorFields(res)).containsExactly("list.userHandle")
    }

    @Test
    fun list_notBase64Url_isInvalidInput() {
        val res = exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=$NOT_BASE64URL", null)
        assertError(res, 400, "C001", "base64url 형식이 아님")
    }

    // ------------------------------------------------------------
    // POST /api/v1/rp/credentials/{credentialId}/label
    // ------------------------------------------------------------

    @Test
    fun rename_unknownCredential_isEntityNotFound() {
        val res = exchange(
            HttpMethod.POST,
            "/api/v1/rp/credentials/$UNKNOWN_CREDENTIAL_ID/label", renameBody(USER_HANDLE),
        )
        assertThat(res.statusCode.value()).isEqualTo(404)
        assertThat(mapper.readTree(res.body).path("code").asString()).isEqualTo("C003")
    }

    @Test
    fun rename_notBase64UrlCredentialId_isInvalidInput() {
        val res = exchange(HttpMethod.POST, "/api/v1/rp/credentials/$NOT_BASE64URL/label", renameBody(USER_HANDLE))
        assertError(res, 400, "C001", "base64url 형식이 아님")
    }

    @Test
    fun rename_notBase64UrlUserHandle_isInvalidInput() {
        val res = exchange(HttpMethod.POST, "/api/v1/rp/credentials/$UNKNOWN_CREDENTIAL_ID/label", renameBody("+++"))
        assertError(res, 400, "C001", "base64url 형식이 아님")
    }

    // ------------------------------------------------------------
    // DELETE /api/v1/rp/credentials/{credentialId}
    // ------------------------------------------------------------

    @Test
    fun delete_missingUserHandle_isMissingParameter() {
        val res = exchange(HttpMethod.DELETE, "/api/v1/rp/credentials/$UNKNOWN_CREDENTIAL_ID", null)
        assertError(
            res, 400, "C004",
            "Required request parameter 'userHandle' for method parameter type String is not present",
        )
    }

    @Test
    fun delete_blankUserHandle_isConstraintViolationWithMethodPath() {
        val res = exchange(HttpMethod.DELETE, "/api/v1/rp/credentials/$UNKNOWN_CREDENTIAL_ID?userHandle=", null)
        assertError(res, 400, "C001", "Invalid input value")
        assertThat(fieldErrorFields(res)).containsExactly("delete.userHandle")
    }

    @Test
    fun delete_unknownCredential_isEntityNotFound() {
        val res = exchange(
            HttpMethod.DELETE,
            "/api/v1/rp/credentials/$UNKNOWN_CREDENTIAL_ID?userHandle=$USER_HANDLE", null,
        )
        assertThat(res.statusCode.value()).isEqualTo(404)
        assertThat(mapper.readTree(res.body).path("code").asString()).isEqualTo("C003")
    }

    @Test
    fun delete_notBase64UrlCredentialId_isInvalidInput() {
        val res = exchange(HttpMethod.DELETE, "/api/v1/rp/credentials/$NOT_BASE64URL?userHandle=$USER_HANDLE", null)
        assertError(res, 400, "C001", "base64url 형식이 아님")
    }

    @Test
    fun delete_registeredCredential_returnsOkEnvelopeAndRemovesIt() {
        register()
        val listed = data(exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=$USER_HANDLE", null))
        assertThat(listed).hasSize(1)
        val credentialId = listed.get(0).get("credentialId").asString()

        val res = exchange(HttpMethod.DELETE, "/api/v1/rp/credentials/$credentialId?userHandle=$USER_HANDLE", null)
        assertThat(res.statusCode.value()).isEqualTo(200)
        assertThat(res.headers.contentType).isNotNull()
            .satisfies(ThrowingConsumer {
                assertThat(checkNotNull(it).isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue()
            })
        val tree = mapper.readTree(res.body)
        assertThat(tree.path("success").asBoolean()).isTrue()
        assertThat(tree.has("data")).`as`("void ok() omits data: %s", res.body).isFalse()

        assertThat(data(exchange(HttpMethod.GET, "/api/v1/rp/credentials?userHandle=$USER_HANDLE", null))).isEmpty()
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------

    private fun register() {
        val authn = Fido2TestAuthenticator(Origin.create(origin()), mapper)
        val start = data(
            exchange(
                HttpMethod.POST, "/api/v1/rp/registration/start",
                """{"userHandle":"$USER_HANDLE","displayName":"Cred User","username":"cred-user"}""",
            ),
        )
        val body = mapper.createObjectNode()
        body.put("registrationToken", start.get("registrationToken").asString())
        body.set("publicKeyCredential", authn.register(start.get("publicKeyCredentialCreationOptions")))
        data(exchange(HttpMethod.POST, "/api/v1/rp/registration/finish", mapper.writeValueAsString(body)))
    }

    private fun renameBody(userHandle: String) = """{"userHandle":"$userHandle","label":"k"}"""

    private fun origin() = "http://localhost:$port"

    private fun exchange(method: HttpMethod, path: String, body: String?): ResponseEntity<String> {
        val h = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-API-Key", PREFIX + SECRET)
        }
        // 쿼리의 %XX 는 이미 인코딩된 값 — URI 로 넘겨 RestTemplate 이 다시 인코딩하지 않게 한다.
        return http.exchange(URI.create("http://localhost:$port$path"), method, HttpEntity(body, h), String::class.java)
    }

    private fun data(res: ResponseEntity<String>): JsonNode {
        assertThat(res.statusCode.value()).`as`("status, body=%s", res.body).isEqualTo(200)
        val tree = mapper.readTree(res.body)
        assertThat(tree.path("success").asBoolean()).`as`("success flag: %s", res.body).isTrue()
        return tree.path("data")
    }

    private fun assertError(res: ResponseEntity<String>, status: Int, code: String, message: String) {
        assertThat(res.statusCode.value()).`as`("status, body=%s", res.body).isEqualTo(status)
        val tree = mapper.readTree(res.body)
        assertThat(tree.path("success").asBoolean()).isFalse()
        assertThat(tree.path("code").asString()).isEqualTo(code)
        assertThat(tree.path("message").asString()).`as`("body=%s", res.body).isEqualTo(message)
    }

    private fun fieldErrorFields(res: ResponseEntity<String>): List<String> =
        mapper.readTree(res.body).path("error").path("fieldErrors").values().map { it.path("field").asString() }
}
