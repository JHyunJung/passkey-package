package com.crosscert.passkey.app.config

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.StreamReadConstraints

/** 등록/인증 JSON 바디가 정상적으로 이 상한을 넘을 일은 없다(공개키·속성 포함 수십 KB 수준). */
private const val MAX_NESTING_DEPTH = 64
private const val MAX_STRING_LENGTH = 256 * 1024 // 256KB
private const val MAX_DOCUMENT_LENGTH = 1024L * 1024 // 1MB

/**
 * G02 방어심화: /registration/finish 등의 `publicKeyCredential` 은 컨트롤러
 * 진입 전 Jackson 이 `JsonNode` 트리로 완전 파싱한다. RegistrationFinishService
 * 의 64KB 문자열 재직렬화 상한(verifyRegistration 내부)은 트리가 이미 다 만들어진
 * *이후*에만 적용되므로, 트리 구성 자체의 파싱 비용(깊은 중첩·거대 문자열)은
 * 막지 못한다 — 인증 필요 + RateLimitFilter 토큰버킷으로 실질 영향은 제한적이지만
 * 방어심화로 파서 레벨 상한을 둔다.
 *
 * Jackson 기본값 대비 등록 요청 실측 크기에 맞춰 보수적으로 좁힌다. Jackson 3 에서
 * 파서 상한은 `JsonFactory` 빌더에 설정하며, Boot 4 는 자동설정 `JsonFactory`
 * (MVC JSON 컨버터와 주입되는 `JsonMapper` 가 공유)를 만들 때
 * [JsonFactoryBuilderCustomizer] 빈을 순서대로 적용한다. 이 빈은 순서 미지정(최저 우선순위)
 * 이라 Boot 표준 커스터마이저(`spring.jackson.factory.constraints.*`, order 0) 뒤에 실행되어
 * 그 값을 덮는다. core 의 [com.crosscert.passkey.core.config.CoreJacksonConfig]
 * (모든 모듈 공통)와 별개로 passkey-app 에만 적용된다.
 */
@Configuration
class PasskeyAppJacksonConfig {

    @Bean
    fun jsonDosJacksonCustomizer(): JsonFactoryBuilderCustomizer {
        val constraints = StreamReadConstraints.builder()
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .maxStringLength(MAX_STRING_LENGTH)
            .maxDocumentLength(MAX_DOCUMENT_LENGTH)
            .build()
        return JsonFactoryBuilderCustomizer { builder -> builder.streamReadConstraints(constraints) }
    }
}
