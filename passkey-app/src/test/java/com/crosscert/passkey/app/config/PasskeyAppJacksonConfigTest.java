package com.crosscert.passkey.app.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G02 — passkey-app 은 등록 finish 의 publicKeyCredential 을 JsonNode 트리로
 * 완전 파싱하기 전에, Jackson 파서 레벨에서 과대 입력(깊은 중첩·거대 문자열)을
 * 조기 거부해야 한다(등록 finish DoS 표면). PasskeyAppJacksonConfig 가 등록하는
 * Jackson2ObjectMapperBuilderCustomizer 가 StreamReadConstraints 를 보수적으로
 * 좁혀 트리 완전 구성 전에 파서 단에서 거부하는지 검증한다.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 Jackson2ObjectMapperBuilder 에 커스터마이저를
 * 직접 적용해 빌드된 ObjectMapper 의 실제 동작을 검증한다(가장 빠르고 확실).
 */
class PasskeyAppJacksonConfigTest {

    private ObjectMapper buildMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new PasskeyAppJacksonConfig().jsonDosJacksonCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    void rejectsDeeplyNestedJson_beforeFullTreeParse() throws Exception {
        ObjectMapper mapper = buildMapper();
        StreamReadConstraints constraints = mapper.getFactory().streamReadConstraints();
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
        StreamReadConstraints constraints = mapper.getFactory().streamReadConstraints();
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
}
