package com.crosscert.passkey.app.contract

import com.crosscert.passkey.app.fido2.Fido2TestAuthenticator
import com.crosscert.passkey.app.support.PasskeyAppItSupport
import com.crosscert.passkey.app.support.PasskeyAppItSupport.TenantSeeder
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.webauthn4j.data.client.Origin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
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
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.regex.Pattern

private val RECORD = java.lang.Boolean.getBoolean("contract.record")
private val SNAPSHOT_DIR: Path = Path.of("src/test/resources/contract")

/** JWT 세그먼트 구분자 — Java String.split("\\.") 와 같은 의미(Pattern.split, limit 0). */
private val DOT: Pattern = Pattern.compile("\\.")

private const val TENANT_HEX = "cccccccccccccccccccccccccccccccc"
private const val PREFIX = "pk_cccccccc"
private const val SECRET = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCC3"
private val USER_HANDLE: String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(byteArrayOf(21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36))

/**
 * passkey-app 외부 API 의 JSON 계약 스냅샷 (Boot 4 업그레이드 안전망).
 *
 * 각 응답 바디를 [JsonShape] 로 정규화해 `src/test/resources/contract/<케이스>.shape.json`
 * 과 비교한다 — 필드명·필드 존재(null 생략 포함)·값 타입·날짜 오프셋·에러 봉투가 계약이다.
 * 스냅샷은 업그레이드 전(Boot 3.5)에 기록했고 이후 수정하지 않는다(수정 = 계약 변경).
 *
 * 기록: `./gradlew :passkey-app:test --tests '*ApiContractIT' -Pcontract.record=true`
 * — 파일을 쓰고 테스트는 실패한다(기록 후 내용을 눈으로 검토). 기본 모드는 비교만 하며
 * 스냅샷이 없거나 다르면 실패한다.
 *
 * 컨테이너·테넌트·API Key 준비는 [PasskeyAppItSupport](`Fido2EndToEndIT` 와 같은 절차, 테넌트 1개).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ApiContractIT @Autowired constructor(
    @LocalServerPort private val port: Int,
    private val encoder: PasswordEncoder,
    private val mapper: ObjectMapper,
    private val redisFactory: RedisConnectionFactory,
) {

    companion object {
        // ------------------------------------------------------------
        // Containers / seed (절차는 PasskeyAppItSupport — Fido2EndToEndIT 와 동일)
        // ------------------------------------------------------------

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

    // JDK HttpClient 기반 — 기본 HttpURLConnection 은 POST 401 응답의 바디를 버린다
    // (streaming mode 에서 getErrorStream()=null). 에러 봉투 형태를 보려면 바디가 필요하다.
    private val http = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(res: ClientHttpResponse) = false // 4xx/5xx 도 그대로 받아 상태·바디를 단언한다.
        }
    }

    @BeforeEach
    fun seed() {
        TenantContextHolder.clear()
        val s = seeder ?: TenantSeeder(ORACLE, "contract-it-admin-pool").also { seeder = it }
        s.wipe()
        s.seedTenant(TENANT_HEX, "contract", "Contract Tenant", origin(), PREFIX, encoder.encode(SECRET))
    }

    @AfterEach
    fun cleanup() {
        TenantContextHolder.clear()
        seeder?.wipe()
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    private fun origin() = "http://localhost:$port"

    // ------------------------------------------------------------
    // actuator — passkey-app 은 Spring Security 가 없어 health 가 무인증이다.
    // show-details: when-authorized 이므로 DB·Redis 구성 요소 상세가 나가면 안 된다.
    // ------------------------------------------------------------

    @Test
    fun actuatorHealth_unauthenticated_exposesStatusOnly() {
        val res = http.getForEntity("http://localhost:$port/actuator/health", String::class.java)
        assertThat(res.statusCode.value()).isEqualTo(200)
        val body = mapper.readTree(res.body)
        assertThat(body.path("status").asString()).isEqualTo("UP")
        assertThat(body.has("components")).`as`("구성 요소 상세 비노출").isFalse()
        assertThat(body.has("details")).isFalse()
    }

    // ------------------------------------------------------------
    // Happy-path shapes
    // ------------------------------------------------------------

    @Test
    fun registrationStart_shape() {
        val res = post("/api/v1/rp/registration/start", registrationStartBody())
        assertOkJson(res)
        assertMatchesSnapshot("registration-start", res.body)
    }

    @Test
    fun registrationFinish_shape() {
        val res = Ceremony().registerFinishRaw()
        assertOkJson(res)
        assertMatchesSnapshot("registration-finish", res.body)
    }

    @Test
    fun authenticationStart_shape() {
        val c = Ceremony()
        c.register()
        val res = c.authenticationStartRaw()
        assertOkJson(res)
        assertMatchesSnapshot("authentication-start", res.body)
    }

    @Test
    fun authenticationFinish_shape() {
        val c = Ceremony()
        c.register()
        val res = c.authenticationFinishRaw()
        assertOkJson(res)
        assertMatchesSnapshot("authentication-finish", res.body)
    }

    @Test
    fun idToken_claimShape() {
        val c = Ceremony()
        c.register()
        val idToken = data(c.authenticationFinishRaw()).get("idToken").asString()

        // Java String.split("\\.") 와 같은 규칙(정규식, 끝 빈 세그먼트 제거, 구분자 없으면 원문 1개) — Kotlin split('.') 은 빈 꼬리를 남긴다.
        val parts = DOT.split(idToken)
        assertThat(parts).hasSize(3)
        val decoded = mapper.createObjectNode()
        decoded.set("header", mapper.readTree(Base64.getUrlDecoder().decode(parts[0])))
        decoded.set("payload", mapper.readTree(Base64.getUrlDecoder().decode(parts[1])))
        assertMatchesSnapshot("id-token-claims", mapper.writeValueAsString(decoded))
    }

    @Test
    fun credentials_shape() {
        // register → authenticate(lastUsedAt 채움) → label 지정 → 목록. CredentialView 의 4개 필드가
        // 모두 non-null 이 되도록 만들어 null 생략 여부와 무관하게 전체 형태를 고정한다.
        val c = Ceremony()
        c.register()
        c.authenticationFinishRaw()
        val listed = data(get("/api/v1/rp/credentials?userHandle=$USER_HANDLE"))
        val credentialId = listed.get(0).get("credentialId").asString()
        val renamed = post(
            "/api/v1/rp/credentials/$credentialId/label",
            """{"userHandle":"$USER_HANDLE","label":"contract key"}""",
        )
        assertOkJson(renamed)

        val res = get("/api/v1/rp/credentials?userHandle=$USER_HANDLE")
        assertOkJson(res)
        assertMatchesSnapshot("credentials-list", res.body)
    }

    @Test
    fun credentialsAfterRegistration_omitsNullFields() {
        // 등록 직후(인증·label 지정 전): label·lastUsedAt 이 null 이다. non_null 직렬화 계약상
        // 두 필드는 null 로 나오지 않고 아예 생략되어야 한다 — 스냅샷에 두 경로가 없어야 한다.
        Ceremony().register()

        val res = get("/api/v1/rp/credentials?userHandle=$USER_HANDLE")
        assertOkJson(res)
        val first = mapper.readTree(res.body).get("data").get(0)
        assertThat(first.has("label")).`as`("null label must be omitted: %s", first).isFalse()
        assertThat(first.has("lastUsedAt")).`as`("null lastUsedAt must be omitted: %s", first).isFalse()
        assertMatchesSnapshot("credentials-list-after-registration", res.body)
    }

    @Test
    fun jwks_shape() {
        val res = http.exchange(url("/.well-known/jwks.json"), HttpMethod.GET, HttpEntity.EMPTY, String::class.java)
        assertOkJson(res)
        assertMatchesSnapshot("jwks", res.body)
    }

    // ------------------------------------------------------------
    // Error envelopes
    // ------------------------------------------------------------

    @Test
    fun missingApiKey_errorShape() {
        val h = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val res = http.exchange(
            url("/api/v1/rp/registration/start"),
            HttpMethod.POST, HttpEntity(registrationStartBody(), h), String::class.java,
        )
        assertThat(res.statusCode.value()).isEqualTo(401)
        assertThat(res.headers.contentType)
            .isNotNull()
            .satisfies(ThrowingConsumer {
                assertThat(checkNotNull(it).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
            })
        assertMatchesSnapshot("error-missing-api-key", res.body)
    }

    @Test
    fun validationError_errorShape() {
        val res = post("/api/v1/rp/registration/start", """{"userHandle":"","displayName":"","username":""}""")
        assertErrorEnvelope(res, 400, "C001")
        assertMatchesSnapshot("error-validation", res.body)
    }

    @Test
    fun unknownToken_errorShape() {
        val res = post(
            "/api/v1/rp/registration/finish",
            """{"registrationToken":"no-such-token","publicKeyCredential":{}}""",
        )
        assertErrorEnvelope(res, 400, "C001")
        assertMatchesSnapshot("error-unknown-token", res.body)
    }

    @Test
    fun unknownRequestField_isRejected() {
        val body = mapper.readTree(registrationStartBody()) as ObjectNode
        body.put("bogus", 1)
        val res = post("/api/v1/rp/registration/start", mapper.writeValueAsString(body))
        assertErrorEnvelope(res, 400, "C001")
        assertMatchesSnapshot("error-unknown-field", res.body)
    }

    @Test
    fun overDeepJson_isRejected() {
        // 바깥 객체(깊이 1) + 배열 64겹 = 깊이 65 → StreamReadConstraints(maxNestingDepth=64) 초과.
        val tooDeep = post("/api/v1/rp/registration/finish", nestedFinishBody(64))
        assertErrorEnvelope(tooDeep, 400, "C001")
        assertThat(mapper.readTree(tooDeep.body).get("message").asString())
            .isEqualTo("Malformed JSON request")

        // 대조군: 깊이 64 는 파서를 통과해 토큰 검증 단계에서 거부된다(파서 거부가 아님을 확인).
        val atLimit = post("/api/v1/rp/registration/finish", nestedFinishBody(63))
        assertErrorEnvelope(atLimit, 400, "C001")
        assertThat(mapper.readTree(atLimit.body).get("message").asString())
            .isNotEqualTo("Malformed JSON request")
    }

    // ------------------------------------------------------------
    // Ceremony helper (등록 → 인증, 한 authenticator 인스턴스 공유)
    // ------------------------------------------------------------

    private inner class Ceremony {
        private val authn = Fido2TestAuthenticator(Origin.create(origin()), mapper)

        fun registerFinishRaw(): ResponseEntity<String> {
            val start = data(post("/api/v1/rp/registration/start", registrationStartBody()))
            val body = mapper.createObjectNode()
            body.put("registrationToken", start.get("registrationToken").asString())
            body.set("publicKeyCredential", authn.register(start.get("publicKeyCredentialCreationOptions")))
            return post("/api/v1/rp/registration/finish", mapper.writeValueAsString(body))
        }

        fun register() {
            data(registerFinishRaw())
        }

        fun authenticationStartRaw(): ResponseEntity<String> =
            post("/api/v1/rp/authentication/start", """{"userHandle":"$USER_HANDLE"}""")

        fun authenticationFinishRaw(): ResponseEntity<String> {
            val start = data(authenticationStartRaw())
            val body = mapper.createObjectNode()
            body.put("authenticationToken", start.get("authenticationToken").asString())
            body.set("publicKeyCredential", authn.authenticate(start.get("publicKeyCredentialRequestOptions")))
            return post("/api/v1/rp/authentication/finish", mapper.writeValueAsString(body))
        }
    }

    // ------------------------------------------------------------
    // HTTP / assertion helpers
    // ------------------------------------------------------------

    private fun registrationStartBody() =
        """{"userHandle":"$USER_HANDLE","displayName":"Contract User","username":"contract-user"}"""

    private fun nestedFinishBody(arrayDepth: Int) =
        """{"registrationToken":"no-such-token","publicKeyCredential":""" +
            "[".repeat(arrayDepth) + "]".repeat(arrayDepth) + "}"

    private fun url(path: String) = "http://localhost:$port$path"

    private fun authHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-API-Key", PREFIX + SECRET)
    }

    private fun post(path: String, body: String): ResponseEntity<String> =
        http.exchange(url(path), HttpMethod.POST, HttpEntity(body, authHeaders()), String::class.java)

    private fun get(path: String): ResponseEntity<String> =
        // 바디 없는 GET — HttpEntity(headers) 생성자를 명시적으로 고른다.
        http.exchange(url(path), HttpMethod.GET, HttpEntity<Unit>(authHeaders()), String::class.java)

    private fun data(res: ResponseEntity<String>): JsonNode {
        assertOkJson(res)
        val tree = mapper.readTree(res.body)
        assertThat(tree.path("success").asBoolean()).`as`("success flag: %s", res.body).isTrue()
        return tree.path("data")
    }

    private fun assertOkJson(res: ResponseEntity<String>) {
        assertThat(res.statusCode.value()).`as`("status, body=%s", res.body).isEqualTo(200)
        assertThat(res.headers.contentType)
            .isNotNull()
            .satisfies(ThrowingConsumer {
                assertThat(checkNotNull(it).isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue()
            })
    }

    private fun assertErrorEnvelope(res: ResponseEntity<String>, status: Int, code: String) {
        assertThat(res.statusCode.value()).`as`("status, body=%s", res.body).isEqualTo(status)
        val tree = mapper.readTree(res.body)
        assertThat(tree.path("success").asBoolean()).isFalse()
        assertThat(tree.path("code").asString()).isEqualTo(code)
        assertThat(tree.path("error").path("errorCode").asString()).isEqualTo(code)
    }

    /**
     * 응답 바디의 형태를 스냅샷과 비교한다. 비교는 파싱된 트리 동등성(포매팅 무관)이다.
     * `contract.record=true` 이면 스냅샷을 쓰고 실패한다.
     */
    private fun assertMatchesSnapshot(name: String, body: String?) {
        val actual = JsonShape.of(checkNotNull(body) { "response body is null" })
        val file = "$name.shape.json"
        if (RECORD) {
            Files.createDirectories(SNAPSHOT_DIR)
            Files.writeString(SNAPSHOT_DIR.resolve(file), actual, Charsets.UTF_8)
            fail<Unit>("recorded snapshot ${SNAPSHOT_DIR.resolve(file)} — review it, then re-run without -Pcontract.record=true")
        }
        val expected = ApiContractIT::class.java.getResourceAsStream("/contract/$file")
            ?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            ?: fail("missing snapshot contract/$file — record it with -Pcontract.record=true")
        assertThat(mapper.readTree(actual))
            .`as`("contract shape '%s' changed.%nexpected:%n%s%nactual:%n%s", name, expected, actual)
            .isEqualTo(mapper.readTree(expected))
    }
}
