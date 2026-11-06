package com.crosscert.passkey.webauthn;

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

    private JsonMappers() {}

    /**
     * webauthn 내부 파싱용 ObjectMapper.
     *
     * <p>현재 동작 보존: Jackson 3 는 기본값 일부가 Jackson 2 와 다르다(예: 후행 토큰 거부
     * {@code FAIL_ON_TRAILING_TOKENS}, null→primitive 실패). Jackson 2 의 {@code new ObjectMapper()}
     * 와 같은 파싱 의미를 유지하도록 Jackson 이 제공하는 {@code configureForJackson2()} 로 맞춘다.
     */
    public static ObjectMapper secure() {
        return JsonMapper.builder().configureForJackson2().build();
    }
}
