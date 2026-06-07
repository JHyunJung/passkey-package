package com.crosscert.passkey.core.ceremony;

/**
 * credential_auth_event.failure_reason 의 문자열 단일 출처(result=FAILED 일 때만 의미).
 * passkey-app AuthenticationFinishService(producer)가 실패 지점별로 참조한다
 * (CeremonyAction 과 동일 패턴 — 문자열 drift 방지).
 */
public final class CredentialAuthFailureReason {
    /** signCount 가 전진하지 않음(복제 authenticator 의심). */
    public static final String SIGN_COUNT_REPLAY = "SIGN_COUNT_REPLAY";
    /** webauthn4j 서명/검증 실패(manager.verify 예외). */
    public static final String SIGNATURE_INVALID = "SIGNATURE_INVALID";

    private CredentialAuthFailureReason() {}
}
