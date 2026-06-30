package com.crosscert.passkey.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * webauthn 내부 JSON 파싱용 공통 {@link ObjectMapper} 팩토리.
 *
 * <p>모듈 내부에서 직접 생성하던 {@code new ObjectMapper()} 호출을 한곳으로 모아
 * JSON 파싱 정책 드리프트를 제거한다. 현재는 기본 설정을 그대로 반환해 동작이 불변이며,
 * 향후 보안 강화(예: max string length, FAIL_ON_* 등)가 필요하면 이 메서드 한 곳에서만
 * 조정해 모든 파싱 경로에 일관 적용한다.
 */
public final class JsonMappers {

    private JsonMappers() {}

    /**
     * webauthn 내부 파싱용 ObjectMapper.
     *
     * <p>현재 동작 보존: 기본 설정을 유지한다(강화 없음).
     */
    public static ObjectMapper secure() {
        return new ObjectMapper();
    }
}
