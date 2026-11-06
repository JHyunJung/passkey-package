package com.crosscert.passkey.core.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * 자동설정 Jackson 3 {@code JsonMapper}(MVC JSON 컨버터·주입되는 ObjectMapper 공통)를
 * Boot 3 / Jackson 2 시절과 같은 직렬화·역직렬화 의미로 고정한다.
 *
 * <p>기본 골격은 {@code application-common.yml} 의 {@code spring.jackson.use-jackson2-defaults: true}
 * (Boot 가 {@code MapperBuilder.configureForJackson2()} 호출 후 Boot 2/3 의 Jackson 2 기본값 —
 * 날짜/기간 timestamp 끔, FAIL_ON_UNKNOWN_PROPERTIES 끔, DEFAULT_VIEW_INCLUSION 끔 — 을 적용).
 * 그 위에 yml 이 {@code write-dates-as-timestamps: false}, {@code fail-on-unknown-properties: true},
 * {@code default-property-inclusion: non_null} 을 얹는다.
 *
 * <p>이 커스터마이저는 Boot 의 표준 커스터마이저(order 0) 뒤에 실행되며(순서 미지정 = 최저 우선순위),
 * use-jackson2-defaults 가 덮지 않는 차이를 명시로 메우고, 계약상 핵심인 두 설정을 yml 과 무관하게
 * 한 번 더 고정한다(설정 파일 누락 시에도 계약 유지 — Boot 3 시절 이 클래스가 하던 역할 그대로).
 * <ul>
 *   <li>{@link MapperFeature#DETECT_PARAMETER_NAMES} 켬 — {@code configureForJackson2()} 는 이를 끄지만,
 *       Boot 3 는 jackson-module-parameter-names 를 자동 등록해 생성자 파라미터 이름 감지가 켜져 있었다.</li>
 *   <li>{@link JsonWriteFeature#COMBINE_UNICODE_SURROGATES_IN_UTF8} 끔 — Jackson 3 기본값(켬)은 BMP 밖 문자
 *       (이모지 등)를 4바이트 UTF-8 로 쓰지만, Jackson 2 는 {@code 😀} 이스케이프로 썼다.
 *       응답 바이트를 업그레이드 전과 같게 유지한다.</li>
 * </ul>
 */
@Configuration
public class CoreJacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.DETECT_PARAMETER_NAMES)
                .disable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8);
    }
}
