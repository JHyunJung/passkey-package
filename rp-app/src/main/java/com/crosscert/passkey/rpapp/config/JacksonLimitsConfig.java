package com.crosscert.passkey.rpapp.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON 파서 상한(방어심화). {@code /passkey/register/finish} 등은 브라우저가 보낸 {@code publicKeyCredential} 을
 * 컨트롤러 진입 전에 Jackson 이 트리로 완전히 파싱하므로, 깊은 중첩·거대 문자열의 파싱 비용을 파서 단계에서 끊는다.
 * passkey-app({@code PasskeyAppJacksonConfig})과 같은 값이다.
 *
 * <p>rp-app 의 MVC 컨버터와 ObjectMapper 빈은 Jackson 2 라({@code preferred-json-mapper: jackson2}) Jackson 2 빌더에
 * 건다. 문서 전체 크기 상한은 두지 않는다 — 같은 빈이 {@code InMemoryUserStore} 의 사용자 파일 로드에도 쓰여
 * 사용자 수에 비례해 커질 수 있다(요청 하나의 크기는 중첩·문자열 상한으로 충분히 묶인다).
 */
@Configuration
public class JacksonLimitsConfig {

    static final int MAX_NESTING_DEPTH = 64;
    static final int MAX_STRING_LENGTH = 256 * 1024; // 256KB

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonDosLimitsCustomizer() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .build();
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(constraints));
    }
}
