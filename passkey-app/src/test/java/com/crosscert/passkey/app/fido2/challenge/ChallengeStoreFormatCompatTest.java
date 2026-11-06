package com.crosscert.passkey.app.fido2.challenge;

import com.crosscert.passkey.app.config.PasskeyAppJacksonConfig;
import com.crosscert.passkey.core.config.CoreJacksonConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 에 저장되는 ChallengeStore JSON 형식 고정 (Boot 4 업그레이드 안전망).
 *
 * <p>롤링 배포 중에는 구버전이 쓴 challenge 를 신버전이 읽고(그 반대도) 하므로, 직렬화 형식
 * (필드명, byte[] → base64, Instant → ISO-8601 문자열 등)이 바뀌면 진행 중인 ceremony 가 깨진다.
 * 픽스처({@code contract/redis/*.json})는 업그레이드 전 직렬화기로 기록했고 이후 수정하지 않는다.
 *
 * <p>ObjectMapper 는 운영과 같은 경로로 만든다: Boot 의 Jackson 자동구성 + core/passkey-app
 * 커스터마이저 + {@code application.yml}(→ {@code application-common.yml}) 의 {@code spring.jackson.*}.
 * ChallengeStore 의 (de)serialize 는 private 이라 {@link StringRedisTemplate} mock 으로
 * {@code set}/{@code getAndDelete} 를 경유해 검증한다.
 *
 * <p>기록: {@code ./gradlew :passkey-app:test --tests '*ChallengeStoreFormatCompatTest' -Pcontract.record=true}
 */
class ChallengeStoreFormatCompatTest {

    private static final boolean RECORD = Boolean.getBoolean("contract.record");
    private static final Path FIXTURE_DIR = Path.of("src/test/resources/contract/redis");

    private static final RegistrationChallenge REGISTRATION = new RegistrationChallenge(
            "cccccccc-cccc-cccc-cccc-cccccccccccc",
            bytes(32, 1),
            bytes(16, 101),
            "Contract User",
            "contract-user",
            Instant.parse("2026-11-06T01:02:03.123456789Z"));

    private static final AuthenticationChallenge AUTHENTICATION = new AuthenticationChallenge(
            "cccccccc-cccc-cccc-cccc-cccccccccccc",
            bytes(32, 51),
            bytes(16, 101),
            Instant.parse("2026-11-06T01:02:04.5Z"));

    private ObjectMapper mapper;
    private ValueOperations<String, String> ops;
    private ChallengeStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AtomicReference<ObjectMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(CoreJacksonConfig.class, PasskeyAppJacksonConfig.class)
                .run(ctx -> ref.set(ctx.getBean(JsonMapper.class)));
        mapper = ref.get();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        store = new ChallengeStore(redis, mapper);
    }

    @Test
    void readsRecordedRegistrationChallenge() throws IOException {
        when(ops.getAndDelete("challenge:reg:t")).thenReturn(fixture("registration-challenge.json"));

        RegistrationChallenge read = store.takeRegistration("t").orElseThrow();

        assertThat(read.tenantId()).isEqualTo(REGISTRATION.tenantId());
        assertThat(read.challenge()).isEqualTo(REGISTRATION.challenge());
        assertThat(read.userHandle()).isEqualTo(REGISTRATION.userHandle());
        assertThat(read.displayName()).isEqualTo(REGISTRATION.displayName());
        assertThat(read.username()).isEqualTo(REGISTRATION.username());
        assertThat(read.issuedAt()).isEqualTo(REGISTRATION.issuedAt());
    }

    @Test
    void readsRecordedAuthenticationChallenge() throws IOException {
        when(ops.getAndDelete("challenge:auth:t")).thenReturn(fixture("authentication-challenge.json"));

        AuthenticationChallenge read = store.takeAuthentication("t").orElseThrow();

        assertThat(read.tenantId()).isEqualTo(AUTHENTICATION.tenantId());
        assertThat(read.challenge()).isEqualTo(AUTHENTICATION.challenge());
        assertThat(read.userHandle()).isEqualTo(AUTHENTICATION.userHandle());
        assertThat(read.issuedAt()).isEqualTo(AUTHENTICATION.issuedAt());
    }

    @Test
    void writesSameJsonAsRecorded_registration() throws IOException {
        store.putRegistration("t", REGISTRATION);
        assertWrittenMatches("challenge:reg:t", "registration-challenge.json");
    }

    @Test
    void writesSameJsonAsRecorded_authentication() throws IOException {
        store.putAuthentication("t", AUTHENTICATION);
        assertWrittenMatches("challenge:auth:t", "authentication-challenge.json");
    }

    private void assertWrittenMatches(String key, String fixtureName) throws IOException {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(key), json.capture(), eq(Duration.ofMinutes(5)));
        String written = json.getValue();
        if (RECORD) {
            Files.createDirectories(FIXTURE_DIR);
            Files.writeString(FIXTURE_DIR.resolve(fixtureName), written + "\n", StandardCharsets.UTF_8);
            fail("recorded fixture " + FIXTURE_DIR.resolve(fixtureName)
                    + " — review it, then re-run without -Pcontract.record=true");
        }
        String recorded = fixture(fixtureName);
        assertThat(mapper.readTree(written))
                .as("Redis challenge JSON changed.%nrecorded: %s%nwritten:  %s", recorded, written)
                .isEqualTo(mapper.readTree(recorded));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = ChallengeStoreFormatCompatTest.class
                .getResourceAsStream("/contract/redis/" + name)) {
            if (in == null) {
                fail("missing fixture contract/redis/" + name + " — record it with -Pcontract.record=true");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /** 결정적 테스트 바이트: start, start+1, ... (len 개). */
    private static byte[] bytes(int len, int start) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) (start + i);
        return b;
    }
}
