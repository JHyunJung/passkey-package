package com.crosscert.passkey.webauthn;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * webauthn 내부 JSON 파싱용 공통 {@link ObjectMapper} 팩토리.
 *
 * <p>모듈 내부에서 직접 생성하던 {@code new ObjectMapper()} 호출을 한곳으로 모아
 * JSON 파싱 정책 드리프트를 제거한다. 향후 보안 강화(예: max string length, FAIL_ON_* 등)가
 * 필요하면 이 메서드 한 곳에서만 조정해 모든 파싱 경로에 일관 적용한다.
 */
public final class JsonMappers {

    /**
     * Jackson 2(2.21) {@code StreamReadConstraints} 기본값 중 Jackson 3(3.1) 에서 바뀐 두 값.
     * 3.1 기본값은 중첩 500·문자열 100MB. 나머지(문서 길이 무제한, 숫자 1000, 이름 50000,
     * 토큰 수 무제한)는 두 버전이 같다 — 2.21.7 / 3.1.7 jar 의 {@code StreamReadConstraints.defaults()} 로 확인.
     */
    static final int JACKSON2_MAX_NESTING_DEPTH = 1000;
    static final int JACKSON2_MAX_STRING_LENGTH = 20_000_000;

    private JsonMappers() {}

    /**
     * webauthn 내부 파싱용 ObjectMapper.
     *
     * <p>현재 동작 보존: Jackson 3 는 기본값 일부가 Jackson 2 와 다르다. 매퍼 기능(후행 토큰 거부
     * {@code FAIL_ON_TRAILING_TOKENS}, null→primitive 실패 등)은 Jackson 이 제공하는
     * {@code configureForJackson2()} 로, 파서 상한(중첩·문자열 길이)은 팩토리에 Jackson 2 값을
     * 명시해 Jackson 2 의 {@code new ObjectMapper()} 와 같은 파싱 의미를 유지한다.
     */
    public static ObjectMapper secure() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(JACKSON2_MAX_NESTING_DEPTH)
                        .maxStringLength(JACKSON2_MAX_STRING_LENGTH)
                        .build())
                .build();
        return JsonMapper.builder(factory).configureForJackson2().build();
    }
}
