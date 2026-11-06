package com.crosscert.passkey.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약 스냅샷(트리 동등 비교)이 잡지 못하는 바이트 수준 직렬화 형식을 고정한다 — Boot 4 / Jackson 3 전환 안전망.
 *
 * <p>매퍼는 운영 경로와 같게 만든다: Boot 의 Jackson 자동구성 + {@link CoreJacksonConfig} +
 * {@code application-common.yml}(앱의 application.yml 이 {@code spring.config.import} 로 가져오는 공통 설정).
 * 기대값은 Jackson 2(Boot 3.5) 가 같은 입력에 대해 쓰던 바이트 그대로다.
 */
class CoreJacksonWireFormatTest {

    record Dto(String zeta, String alpha, Integer missing, OffsetDateTime at, int mid) {}

    /** getter 기반 POJO — record(생성자 프로퍼티 우선 정렬)와 달리 알파벳 정렬 여부가 그대로 드러난다. */
    static class Pojo {
        public String getZeta() { return "z"; }
        public String getAlpha() { return "a"; }
        public String getMid() { return "m"; }
    }

    private static JsonMapper contextMapper() {
        AtomicReference<JsonMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=classpath:application-common.yml")
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(CoreJacksonConfig.class)
                .run(ctx -> ref.set(ctx.getBean(JsonMapper.class)));
        return ref.get();
    }

    @Test
    void nonBmpCharacter_isWrittenAsSurrogateEscapes_likeJackson2() {
        byte[] out = contextMapper().writeValueAsBytes(new Dto("😀", "a", null, null, 0));
        String raw = new String(out, StandardCharsets.US_ASCII);
        // Jackson 2 의 UTF-8 출력: 이모지 → 6자 이스케이프 두 개(순수 ASCII). Jackson 3 기본값이면 4바이트 UTF-8.
        assertThat(raw).contains("\"zeta\":\"\\uD83D\\uDE00\"");
    }

    @Test
    void recordFields_keepDeclarationOrder_nullsOmitted_isoOffsetDates() {
        String raw = contextMapper().writeValueAsString(new Dto("z", "a", null,
                OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 600_000_000, ZoneOffset.ofHours(9)), 7));
        assertThat(raw).isEqualTo(
                "{\"zeta\":\"z\",\"alpha\":\"a\",\"at\":\"2026-01-02T03:04:05.6+09:00\",\"mid\":7}");
    }

    @Test
    void pojoProperties_areNotSortedAlphabetically() {
        // Jackson 3 기본값(SORT_PROPERTIES_ALPHABETICALLY=true)이면 alpha, mid, zeta 순이 된다.
        assertThat(contextMapper().writeValueAsString(new Pojo()))
                .isEqualTo("{\"zeta\":\"z\",\"alpha\":\"a\",\"mid\":\"m\"}");
    }
}
