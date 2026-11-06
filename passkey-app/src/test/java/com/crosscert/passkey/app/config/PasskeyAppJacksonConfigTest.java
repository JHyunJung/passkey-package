package com.crosscert.passkey.app.config;

import com.crosscert.passkey.core.config.CoreJacksonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonFactoryBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G02 — passkey-app 은 등록 finish 의 publicKeyCredential 을 JsonNode 트리로
 * 완전 파싱하기 전에, Jackson 파서 레벨에서 과대 입력(깊은 중첩·거대 문자열)을
 * 조기 거부해야 한다(등록 finish DoS 표면). PasskeyAppJacksonConfig 가 등록하는
 * JsonFactoryBuilderCustomizer 가 StreamReadConstraints 를 보수적으로
 * 좁혀 트리 완전 구성 전에 파서 단에서 거부하는지 검증한다.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 JsonFactoryBuilder 에 커스터마이저를 직접 적용한 뒤
 * 그 팩토리로 만든 JsonMapper 의 실제 동작을 검증한다(가장 빠르고 확실). Boot 4 자동설정도
 * 같은 순서(팩토리 빌더 커스터마이즈 → JsonMapper.builder(factory))로 매퍼를 만든다.
 *
 * <p>단, 위 단위 테스트는 core yml 의 {@code spring.jackson.factory.constraints.*}(중첩 1000·문자열 20MB)
 * 와의 우선순위를 검증하지 못한다. {@link #productionMapper_passkeyLimitsOverrideCoreYml()} 가 운영과 같은
 * 경로(자동설정 + yml + core/passkey-app 커스터마이저)로 만든 매퍼에서 passkey 상한이 이기는지 고정한다.
 */
class PasskeyAppJacksonConfigTest {

    private ObjectMapper buildMapper() {
        JsonFactoryBuilder builder = JsonFactory.builder();
        new PasskeyAppJacksonConfig().jsonDosJacksonCustomizer().customize(builder);
        return JsonMapper.builder(builder.build()).build();
    }

    @Test
    void rejectsDeeplyNestedJson_beforeFullTreeParse() throws Exception {
        ObjectMapper mapper = buildMapper();
        StreamReadConstraints constraints = mapper.tokenStreamFactory().streamReadConstraints();
        int maxDepth = constraints.getMaxNestingDepth();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxDepth + 10; i++) sb.append("[");
        for (int i = 0; i < maxDepth + 10; i++) sb.append("]");

        assertThatThrownBy(() -> mapper.readTree(sb.toString()))
                .isInstanceOf(StreamConstraintsException.class);
    }

    @Test
    void rejectsOversizedString_beforeFullTreeParse() throws Exception {
        ObjectMapper mapper = buildMapper();
        StreamReadConstraints constraints = mapper.tokenStreamFactory().streamReadConstraints();
        int maxStringLen = constraints.getMaxStringLength();

        String huge = "\"" + "a".repeat(maxStringLen + 1000) + "\"";

        assertThatThrownBy(() -> mapper.readTree(huge))
                .isInstanceOf(StreamConstraintsException.class);
    }

    @Test
    void acceptsNormalSizedJson() throws Exception {
        ObjectMapper mapper = buildMapper();
        var node = mapper.readTree("{\"a\":1,\"b\":[1,2,3],\"c\":\"hello\"}");
        assertThat(node.get("a").asInt()).isEqualTo(1);
    }

    /**
     * 운영 경로 매퍼(JacksonAutoConfiguration + application.yml→application-common.yml + 두 커스터마이저)
     * 에서 passkey-app 의 파서 상한이 core yml 값을 덮는지 확인한다. 덮기는 커스터마이저 순서
     * (passkey 빈 = 최저 우선순위 → Boot 표준 order 0 뒤)에만 의존하므로 컨텍스트 수준에서 고정한다.
     */
    @Test
    void productionMapper_passkeyLimitsOverrideCoreYml() {
        AtomicReference<JsonMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(CoreJacksonConfig.class, PasskeyAppJacksonConfig.class)
                .run(ctx -> ref.set(ctx.getBean(JsonMapper.class)));
        JsonMapper mapper = ref.get();

        // yml 이 실제로 적용됐는지(쓰기 상한은 passkey 가 건드리지 않음) — 아니면 아래 검증이 공허해진다.
        assertThat(mapper.tokenStreamFactory().streamWriteConstraints().getMaxNestingDepth())
                .as("core yml spring.jackson.factory.constraints.write.max-nesting-depth applied")
                .isEqualTo(1000);

        StreamReadConstraints read = mapper.tokenStreamFactory().streamReadConstraints();
        assertThat(read.getMaxNestingDepth()).isEqualTo(64);
        assertThat(read.getMaxStringLength()).isEqualTo(256 * 1024);
        assertThat(read.getMaxDocumentLength()).isEqualTo(1024L * 1024);
    }
}
