package com.crosscert.passkey.core.ceremony;

/**
 * ceremony_event.action 의 문자열 단일 출처.
 * CeremonyEventRecorder(기록)와 admin FunnelService(집계)가 같은 값을 참조해
 * 문자열 불일치로 인한 집계 누락을 막는다. 값은 기존 FunnelService 상수와 동일.
 */
public final class CeremonyAction {
    public static final String REGISTRATION_BEGIN     = "REGISTRATION_BEGIN";
    public static final String REGISTRATION_SUCCESS   = "REGISTRATION_FINISH_OK";
    public static final String AUTHENTICATION_BEGIN   = "AUTHENTICATION_BEGIN";
    public static final String AUTHENTICATION_SUCCESS = "AUTHENTICATION_FINISH_OK";

    private CeremonyAction() {}
}
