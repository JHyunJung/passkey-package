package com.crosscert.passkey.app.fido2.challenge

import com.crosscert.passkey.app.config.PasskeyAppJacksonConfig
import com.crosscert.passkey.core.config.CoreJacksonConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

private val RECORD = java.lang.Boolean.getBoolean("contract.record")
private val FIXTURE_DIR = Path.of("src/test/resources/contract/redis")

private val REGISTRATION = RegistrationChallenge(
    "cccccccc-cccc-cccc-cccc-cccccccccccc",
    bytes(32, 1),
    bytes(16, 101),
    "Contract User",
    "contract-user",
    Instant.parse("2026-11-06T01:02:03.123456789Z"),
)

private val AUTHENTICATION = AuthenticationChallenge(
    "cccccccc-cccc-cccc-cccc-cccccccccccc",
    bytes(32, 51),
    bytes(16, 101),
    Instant.parse("2026-11-06T01:02:04.5Z"),
)

/** 결정적 테스트 바이트: start, start+1, ... (len 개). */
private fun bytes(len: Int, start: Int): ByteArray = ByteArray(len) { (start + it).toByte() }

/**
 * Redis 에 저장되는 ChallengeStore JSON 형식 고정 (Boot 4 업그레이드 안전망).
 *
 * 롤링 배포 중에는 구버전이 쓴 challenge 를 신버전이 읽고(그 반대도) 하므로, 직렬화 형식
 * (필드명, byte[] → base64, Instant → ISO-8601 문자열 등)이 바뀌면 진행 중인 ceremony 가 깨진다.
 * 픽스처(`contract/redis/` 아래 `.json`)는 업그레이드 전 직렬화기로 기록했고 이후 수정하지 않는다.
 *
 * ObjectMapper 는 운영과 같은 경로로 만든다: Boot 의 Jackson 자동구성 + core/passkey-app
 * 커스터마이저 + `application.yml`(→ `application-common.yml`) 의 `spring.jackson.*`.
 * ChallengeStore 의 (de)serialize 는 private 이라 [StringRedisTemplate] mock 으로
 * `set`/`getAndDelete` 를 경유해 검증한다.
 *
 * 기록: `./gradlew :passkey-app:test --tests '*ChallengeStoreFormatCompatTest' -Pcontract.record=true`
 */
class ChallengeStoreFormatCompatTest {

    // 테스트 메서드마다 새 인스턴스(JUnit 기본 수명주기) — 원 @BeforeEach 와 같이 매번 새로 만든다.
    private val mapper: ObjectMapper = runningMapper()
    private val ops: ValueOperations<String, String> = mock()
    private val redis: StringRedisTemplate = mock {
        on { opsForValue() } doReturn ops
    }
    private val store = ChallengeStore(redis, mapper)

    private fun runningMapper(): ObjectMapper {
        var built: ObjectMapper? = null
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
            .withUserConfiguration(CoreJacksonConfig::class.java, PasskeyAppJacksonConfig::class.java)
            .run { ctx -> built = ctx.getBean(JsonMapper::class.java) }
        return checkNotNull(built) { "context run did not capture JsonMapper" }
    }

    @Test
    fun readsRecordedRegistrationChallenge() {
        whenever(ops.getAndDelete("challenge:reg:t")).thenReturn(fixture("registration-challenge.json"))

        val read = store.takeRegistration("t").orElseThrow()

        assertThat(read.tenantId).isEqualTo(REGISTRATION.tenantId)
        assertThat(read.challenge).isEqualTo(REGISTRATION.challenge)
        assertThat(read.userHandle).isEqualTo(REGISTRATION.userHandle)
        assertThat(read.displayName).isEqualTo(REGISTRATION.displayName)
        assertThat(read.username).isEqualTo(REGISTRATION.username)
        assertThat(read.issuedAt).isEqualTo(REGISTRATION.issuedAt)
    }

    @Test
    fun readsRecordedAuthenticationChallenge() {
        whenever(ops.getAndDelete("challenge:auth:t")).thenReturn(fixture("authentication-challenge.json"))

        val read = store.takeAuthentication("t").orElseThrow()

        assertThat(read.tenantId).isEqualTo(AUTHENTICATION.tenantId)
        assertThat(read.challenge).isEqualTo(AUTHENTICATION.challenge)
        assertThat(read.userHandle).isEqualTo(AUTHENTICATION.userHandle)
        assertThat(read.issuedAt).isEqualTo(AUTHENTICATION.issuedAt)
    }

    @Test
    fun writesSameJsonAsRecorded_registration() {
        store.putRegistration("t", REGISTRATION)
        assertWrittenMatches("challenge:reg:t", "registration-challenge.json")
    }

    @Test
    fun writesSameJsonAsRecorded_authentication() {
        store.putAuthentication("t", AUTHENTICATION)
        assertWrittenMatches("challenge:auth:t", "authentication-challenge.json")
    }

    private fun assertWrittenMatches(key: String, fixtureName: String) {
        val json = argumentCaptor<String>()
        verify(ops).set(eq(key), json.capture(), eq(Duration.ofMinutes(5)))
        val written = json.lastValue
        if (RECORD) {
            Files.createDirectories(FIXTURE_DIR)
            Files.writeString(FIXTURE_DIR.resolve(fixtureName), written + "\n", Charsets.UTF_8)
            fail<Unit>(
                "recorded fixture " + FIXTURE_DIR.resolve(fixtureName) +
                    " — review it, then re-run without -Pcontract.record=true",
            )
        }
        val recorded = fixture(fixtureName)
        assertThat(mapper.readTree(written))
            .`as`("Redis challenge JSON changed.%nrecorded: %s%nwritten:  %s", recorded, written)
            .isEqualTo(mapper.readTree(recorded))
    }

    private fun fixture(name: String): String {
        val stream = ChallengeStoreFormatCompatTest::class.java.getResourceAsStream("/contract/redis/$name")
            ?: fail("missing fixture contract/redis/$name — record it with -Pcontract.record=true")
        return stream.use { String(it.readAllBytes(), Charsets.UTF_8).trim() }
    }
}
