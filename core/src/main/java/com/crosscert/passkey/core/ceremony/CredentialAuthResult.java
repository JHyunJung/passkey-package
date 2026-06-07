package com.crosscert.passkey.core.ceremony;

/**
 * credential_auth_event.result 의 문자열 단일 출처.
 * CredentialAuthEventRecorder(기록, passkey-app producer)와 admin 조회/매핑이 같은
 * 값을 참조해 문자열 불일치로 인한 drift 를 막는다(CeremonyAction 과 동일 패턴).
 */
public final class CredentialAuthResult {
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED  = "FAILED";

    private CredentialAuthResult() {}
}
