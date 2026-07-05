package com.crosscert.passkey.app.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * G02 방어심화: /registration/finish 등의 {@code publicKeyCredential} 은 컨트롤러
 * 진입 전 Jackson 이 {@code JsonNode} 트리로 완전 파싱한다. RegistrationFinishService
 * 의 64KB 문자열 재직렬화 상한(verifyRegistration 내부)은 트리가 이미 다 만들어진
 * *이후*에만 적용되므로, 트리 구성 자체의 파싱 비용(깊은 중첩·거대 문자열)은
 * 막지 못한다 — 인증 필요 + RateLimitFilter 토큰버킷으로 실질 영향은 제한적이지만
 * 방어심화로 파서 레벨 상한을 둔다.
 *
 * <p>기본값(문서 20MB, 중첩 1000, 문자열 20MB) 대비 등록 요청 실측 크기에 맞춰
 * 보수적으로 좁힌다. 이 커스터마이저는 core 의 {@link com.crosscert.passkey.core.config.CoreJacksonConfig}
 * (모든 모듈 공통)와 별개로 passkey-app 에만 적용된다 — Spring Boot 는 여러
 * {@link Jackson2ObjectMapperBuilderCustomizer} 빈을 순서대로 모두 적용한다.
 */
@Configuration
public class PasskeyAppJacksonConfig {

    /** 등록/인증 JSON 바디가 정상적으로 이 상한을 넘을 일은 없다(공개키·속성 포함 수십 KB 수준). */
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int MAX_STRING_LENGTH = 256 * 1024; // 256KB
    private static final long MAX_DOCUMENT_LENGTH = 1024 * 1024; // 1MB

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonDosJacksonCustomizer() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                .build();
        return builder -> builder.postConfigurer(
                mapper -> mapper.getFactory().setStreamReadConstraints(constraints));
    }
}
