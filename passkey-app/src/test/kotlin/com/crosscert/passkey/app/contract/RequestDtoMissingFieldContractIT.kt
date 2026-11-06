package com.crosscert.passkey.app.contract

import com.crosscert.passkey.app.support.PasskeyAppItSupport
import com.crosscert.passkey.app.support.PasskeyAppItSupport.TenantSeeder
import com.crosscert.passkey.core.tenant.TenantContextHolder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.stream.Stream

private val RECORD = java.lang.Boolean.getBoolean("contract.record")
private val SNAPSHOT_DIR: Path = Path.of("src/test/resources/contract")

private const val TENANT_HEX = "dddddddddddddddddddddddddddddddd"
private const val PREFIX = "pk_dddddddd"
private const val SECRET = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDD4"
private val USER_HANDLE: String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(byteArrayOf(41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56))

/** 검증 단계에서 끝나는 케이스용 — 존재하지 않는 credentialId (검증 통과 시 서비스가 처리). */
private const val CREDENTIAL_ID = "AAAAAAAAAAAAAAAAAAAAAA"

// ------------------------------------------------------------
// Cases: (snapshot name, path, request body)
// ------------------------------------------------------------

private const val REG_START = "/api/v1/rp/registration/start"
private const val REG_FINISH = "/api/v1/rp/registration/finish"
private const val AUTH_START = "/api/v1/rp/authentication/start"
private const val AUTH_FINISH = "/api/v1/rp/authentication/finish"
private const val RENAME = "/api/v1/rp/credentials/$CREDENTIAL_ID/label"

/**
 * 요청 DTO 의 필드 누락·null 입력에 대한 현재 응답 계약 (passkey-app Kotlin 전환 안전망).
 *
 * Kotlin 전환에서 가장 깨지기 쉬운 지점은 "필드가 없거나 null 인 요청" 이다 — non-null 타입으로
 * 선언하면 Bean Validation 에 닿기 전에 역직렬화가 실패해 에러 봉투(fieldErrors 유무·message)가
 * 바뀐다. 이 테스트는 전환 전(Java) 동작을 스냅샷으로 고정한다.
 *
 * 케이스마다 `contract/missing-field-<케이스>.shape.json` 에 다음을 기록·비교한다:
 * HTTP 상태, 봉투의 `code`·`message`, `error.fieldErrors[].field` 목록(정렬),
 * 응답 바디의 [JsonShape]. fieldErrors 의 `reason` 문구는 JVM 로케일에 따라 달라지는
 * Bean Validation 기본 메시지라 값으로 고정하지 않는다(형태는 shape 에 포함).
 *
 * 기록: `./gradlew :passkey-app:test --tests '*RequestDtoMissingFieldContractIT' -Pcontract.record=true`
 * — 파일을 쓰고 테스트는 실패한다(기록 후 검토). 기본 모드는 비교만 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class RequestDtoMissingFieldContractIT @Autowired constructor(
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

        @JvmStatic
        fun cases(): Stream<Arguments> {
            val uh = "\"$USER_HANDLE\""
            return Stream.of(
                // RegistrationStartRequest: @NotBlank @Size userHandle / displayName / username
                arguments("registration-start-empty", REG_START, "{}"),
                arguments(
                    "registration-start-missing-userHandle", REG_START,
                    """{"displayName":"Missing User","username":"missing-user"}""",
                ),
                arguments(
                    "registration-start-missing-displayName", REG_START,
                    """{"userHandle":$uh,"username":"missing-user"}""",
                ),
                arguments(
                    "registration-start-missing-username", REG_START,
                    """{"userHandle":$uh,"displayName":"Missing User"}""",
                ),
                arguments(
                    "registration-start-null-userHandle", REG_START,
                    """{"userHandle":null,"displayName":"Missing User","username":"missing-user"}""",
                ),
                arguments(
                    "registration-start-null-displayName", REG_START,
                    """{"userHandle":$uh,"displayName":null,"username":"missing-user"}""",
                ),
                arguments(
                    "registration-start-null-username", REG_START,
                    """{"userHandle":$uh,"displayName":"Missing User","username":null}""",
                ),

                // RegistrationFinishRequest: @NotBlank registrationToken / @NotNull JsonNode publicKeyCredential
                arguments("registration-finish-empty", REG_FINISH, "{}"),
                arguments(
                    "registration-finish-missing-registrationToken", REG_FINISH,
                    """{"publicKeyCredential":{}}""",
                ),
                arguments(
                    "registration-finish-missing-publicKeyCredential", REG_FINISH,
                    """{"registrationToken":"no-such-token"}""",
                ),
                arguments(
                    "registration-finish-null-registrationToken", REG_FINISH,
                    """{"registrationToken":null,"publicKeyCredential":{}}""",
                ),
                arguments(
                    "registration-finish-null-publicKeyCredential", REG_FINISH,
                    """{"registrationToken":"no-such-token","publicKeyCredential":null}""",
                ),

                // AuthenticationStartRequest: userHandle — 검증 애너테이션 없음(usernameless 허용)
                arguments("authentication-start-empty", AUTH_START, "{}"),
                arguments("authentication-start-null-userHandle", AUTH_START, """{"userHandle":null}"""),
                arguments("authentication-start-blank-userHandle", AUTH_START, """{"userHandle":""}"""),

                // AuthenticationFinishRequest: @NotBlank authenticationToken / @NotNull JsonNode publicKeyCredential
                arguments("authentication-finish-empty", AUTH_FINISH, "{}"),
                arguments(
                    "authentication-finish-missing-authenticationToken", AUTH_FINISH,
                    """{"publicKeyCredential":{}}""",
                ),
                arguments(
                    "authentication-finish-missing-publicKeyCredential", AUTH_FINISH,
                    """{"authenticationToken":"no-such-token"}""",
                ),
                arguments(
                    "authentication-finish-null-authenticationToken", AUTH_FINISH,
                    """{"authenticationToken":null,"publicKeyCredential":{}}""",
                ),
                arguments(
                    "authentication-finish-null-publicKeyCredential", AUTH_FINISH,
                    """{"authenticationToken":"no-such-token","publicKeyCredential":null}""",
                ),

                // CredentialSelfServiceController.RenameRequest: @NotBlank userHandle / @NotBlank @Size label
                arguments("rename-empty", RENAME, "{}"),
                arguments("rename-missing-userHandle", RENAME, """{"label":"missing key"}"""),
                arguments("rename-missing-label", RENAME, """{"userHandle":$uh}"""),
                arguments("rename-null-userHandle", RENAME, """{"userHandle":null,"label":"missing key"}"""),
                arguments("rename-null-label", RENAME, """{"userHandle":$uh,"label":null}"""),
            )
        }

        // ------------------------------------------------------------
        // Coercion / format cases: blank, @Size 초과, JSON 타입 불일치, 빈 바디, 깨진 JSON.
        // jackson-module-kotlin + Kotlin DTO 가 이 강제변환·역직렬화 실패 경로를 바꾸는지 판정한다.
        // 스냅샷: contract/coercion-<케이스>.shape.json
        // ------------------------------------------------------------

        @JvmStatic
        fun coercionCases(): Stream<Arguments> {
            val uh = "\"$USER_HANDLE\""
            val over88 = "\"" + "A".repeat(89) + "\""
            val over128 = "\"" + "a".repeat(129) + "\""
            val over256 = "\"" + "a".repeat(257) + "\""
            return Stream.of(
                // RegistrationStartRequest
                arguments(
                    "registration-start-blank-userHandle", REG_START,
                    """{"userHandle":"","displayName":"Coerce User","username":"coerce-user"}""",
                ),
                arguments(
                    "registration-start-blank-displayName", REG_START,
                    """{"userHandle":$uh,"displayName":"","username":"coerce-user"}""",
                ),
                arguments(
                    "registration-start-blank-username", REG_START,
                    """{"userHandle":$uh,"displayName":"Coerce User","username":""}""",
                ),
                arguments(
                    "registration-start-oversize-userHandle", REG_START,
                    """{"userHandle":$over88,"displayName":"Coerce User","username":"coerce-user"}""",
                ),
                arguments(
                    "registration-start-oversize-displayName", REG_START,
                    """{"userHandle":$uh,"displayName":$over256,"username":"coerce-user"}""",
                ),
                arguments(
                    "registration-start-oversize-username", REG_START,
                    """{"userHandle":$uh,"displayName":"Coerce User","username":$over256}""",
                ),
                arguments(
                    "registration-start-number-displayName", REG_START,
                    """{"userHandle":$uh,"displayName":12345,"username":"coerce-user"}""",
                ),
                arguments(
                    "registration-start-array-username", REG_START,
                    """{"userHandle":$uh,"displayName":"Coerce User","username":["coerce-user"]}""",
                ),
                arguments("registration-start-empty-body", REG_START, ""),
                arguments("registration-start-null-body", REG_START, "null"),
                arguments("registration-start-malformed", REG_START, """{"userHandle":"""),

                // RegistrationFinishRequest
                arguments(
                    "registration-finish-blank-registrationToken", REG_FINISH,
                    """{"registrationToken":"","publicKeyCredential":{}}""",
                ),
                arguments(
                    "registration-finish-number-registrationToken", REG_FINISH,
                    """{"registrationToken":12345,"publicKeyCredential":{}}""",
                ),
                arguments(
                    "registration-finish-number-publicKeyCredential", REG_FINISH,
                    """{"registrationToken":"no-such-token","publicKeyCredential":12345}""",
                ),
                arguments(
                    "registration-finish-array-publicKeyCredential", REG_FINISH,
                    """{"registrationToken":"no-such-token","publicKeyCredential":[1,2]}""",
                ),
                arguments("registration-finish-empty-body", REG_FINISH, ""),
                arguments("registration-finish-null-body", REG_FINISH, "null"),
                arguments("registration-finish-malformed", REG_FINISH, """{"registrationToken":"""),

                // AuthenticationStartRequest (검증 애너테이션 없음)
                arguments("authentication-start-number-userHandle", AUTH_START, """{"userHandle":12345}"""),
                arguments("authentication-start-array-userHandle", AUTH_START, """{"userHandle":["x"]}"""),
                arguments("authentication-start-empty-body", AUTH_START, ""),
                arguments("authentication-start-null-body", AUTH_START, "null"),
                arguments("authentication-start-malformed", AUTH_START, """{"userHandle":"""),

                // AuthenticationFinishRequest
                arguments(
                    "authentication-finish-blank-authenticationToken", AUTH_FINISH,
                    """{"authenticationToken":"","publicKeyCredential":{}}""",
                ),
                arguments(
                    "authentication-finish-number-authenticationToken", AUTH_FINISH,
                    """{"authenticationToken":12345,"publicKeyCredential":{}}""",
                ),
                arguments(
                    "authentication-finish-number-publicKeyCredential", AUTH_FINISH,
                    """{"authenticationToken":"no-such-token","publicKeyCredential":12345}""",
                ),
                arguments(
                    "authentication-finish-array-publicKeyCredential", AUTH_FINISH,
                    """{"authenticationToken":"no-such-token","publicKeyCredential":[1,2]}""",
                ),
                arguments("authentication-finish-empty-body", AUTH_FINISH, ""),
                arguments("authentication-finish-null-body", AUTH_FINISH, "null"),
                arguments("authentication-finish-malformed", AUTH_FINISH, """{"authenticationToken":"""),

                // CredentialSelfServiceController.RenameRequest
                arguments("rename-blank-userHandle", RENAME, """{"userHandle":"","label":"coerce key"}"""),
                arguments("rename-blank-label", RENAME, """{"userHandle":$uh,"label":""}"""),
                arguments("rename-oversize-label", RENAME, """{"userHandle":$uh,"label":$over128}"""),
                arguments("rename-number-label", RENAME, """{"userHandle":$uh,"label":12345}"""),
                arguments("rename-array-userHandle", RENAME, """{"userHandle":[$uh],"label":"coerce key"}"""),
                arguments("rename-empty-body", RENAME, ""),
                arguments("rename-null-body", RENAME, "null"),
                arguments("rename-malformed", RENAME, """{"userHandle":"""),
            )
        }
    }

    // JDK HttpClient 기반 — 4xx 응답 바디까지 받는다(ApiContractIT 와 동일 이유).
    private val http = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(res: ClientHttpResponse) = false
        }
    }

    @BeforeEach
    fun seed() {
        TenantContextHolder.clear()
        val s = seeder ?: TenantSeeder(ORACLE, "missing-field-it-admin-pool").also { seeder = it }
        s.wipe()
        s.seedTenant(
            TENANT_HEX, "missing-field", "Missing Field Tenant", origin(), PREFIX,
            encoder.encode(SECRET),
        )
    }

    @AfterEach
    fun cleanup() {
        TenantContextHolder.clear()
        seeder?.wipe()
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    private fun origin() = "http://localhost:$port"

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun missingOrNullField_responseContract(name: String, path: String, body: String) {
        val res = http.exchange(url(path), HttpMethod.POST, HttpEntity(body, authHeaders()), String::class.java)
        assertMatchesSnapshot("missing-field-$name", res)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coercionCases")
    fun coercionOrFormatError_responseContract(name: String, path: String, body: String) {
        val res = http.exchange(url(path), HttpMethod.POST, HttpEntity(body, authHeaders()), String::class.java)
        assertMatchesSnapshot("coercion-$name", res)
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private fun url(path: String) = "http://localhost:$port$path"

    private fun authHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-API-Key", PREFIX + SECRET)
    }

    /** 상태·code·message·fieldErrors 필드 목록·바디 shape 를 하나의 정규 JSON 으로 만든다. */
    private fun contractOf(res: ResponseEntity<String>): String {
        val body = checkNotNull(res.body) { "response body is null" }
        val tree = mapper.readTree(body)
        val out = mapper.createObjectNode()
        out.put("status", res.statusCode.value())
        out.set("code", if (tree.path("code").isMissingNode) null else tree.get("code"))
        out.set("message", if (tree.path("message").isMissingNode) null else tree.get("message"))
        val fieldErrors = tree.path("error").path("fieldErrors")
        if (fieldErrors.isArray) {
            val arr = out.putArray("fieldErrorFields")
            fieldErrors.values().map { it.path("field").asString() }.sorted().forEach { arr.add(it) }
        }
        out.set("shape", mapper.readTree(JsonShape.of(body)))
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n"
    }

    private fun assertMatchesSnapshot(name: String, res: ResponseEntity<String>) {
        val actual = contractOf(res)
        val file = "$name.shape.json"
        if (RECORD) {
            Files.createDirectories(SNAPSHOT_DIR)
            Files.writeString(SNAPSHOT_DIR.resolve(file), actual, Charsets.UTF_8)
            fail<Unit>("recorded snapshot ${SNAPSHOT_DIR.resolve(file)} — review it, then re-run without -Pcontract.record=true")
        }
        val expected = RequestDtoMissingFieldContractIT::class.java.getResourceAsStream("/contract/$file")
            ?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            ?: fail("missing snapshot contract/$file — record it with -Pcontract.record=true")
        assertThat(mapper.readTree(actual))
            .`as`("missing-field contract '%s' changed.%nexpected:%n%s%nactual:%n%s", name, expected, actual)
            .isEqualTo(mapper.readTree(expected))
    }
}
