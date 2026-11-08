package com.crosscert.passkey.admin.web.support

import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.json.JsonMapper

/**
 * 스프링 컨텍스트 밖에서 직접 만드는 Jackson 3 매퍼의 출발점(토스트 헤더·활동 diff 파싱·단위 테스트용 fmt).
 *
 * Jackson 2 의 `new ObjectMapper()` 와 같은 의미로 시작한다. 매퍼 기능 기본값은 Jackson 이
 * 제공하는 `configureForJackson2()` 로(후행 토큰 허용, 날짜 timestamp 출력 등), 파서 상한은
 * Jackson 2(2.21) 기본값(중첩 1000·문자열 20MB — Jackson 3.1 기본은 500·100MB)으로 되돌린다.
 * 호출하는 쪽은 이 빌더 위에 자기 기능만 얹는다(예: `FAIL_ON_TRAILING_TOKENS`, `ESCAPE_NON_ASCII`).
 * webauthn 모듈의 `JsonMappers.secure()` 와 같은 방식이다.
 *
 * Kotlin 모듈(jackson-module-kotlin)은 등록하지 않는다 — 원 Java 와 같은 순수 Jackson 매퍼다.
 */
object StandaloneJson {

    internal const val JACKSON2_MAX_NESTING_DEPTH = 1000
    internal const val JACKSON2_MAX_STRING_LENGTH = 20_000_000

    /** Jackson 2 `new ObjectMapper()` 와 같은 기본값을 가진 빌더. */
    @JvmStatic
    fun jackson2Like(): JsonMapper.Builder {
        val factory = JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(JACKSON2_MAX_NESTING_DEPTH)
                    .maxStringLength(JACKSON2_MAX_STRING_LENGTH)
                    .build(),
            )
            .build()
        return JsonMapper.builder(factory).configureForJackson2()
    }
}
