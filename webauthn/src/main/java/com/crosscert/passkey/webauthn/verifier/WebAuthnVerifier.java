package com.crosscert.passkey.webauthn.verifier;

/**
 * 앱과 검증 구현 사이의 유일한 경계. webauthn4j 타입은 이 경계를
 * 절대 넘지 않는다. 구현체: NativeWebAuthnVerifier(프로덕션),
 * Webauthn4jVerifier(differential 테스트 전용).
 */
public interface WebAuthnVerifier {
    RegistrationResult verifyRegistration(RegistrationInput input)
            throws WebAuthnVerificationException;

    /**
     * assertion(인증)을 검증한다. 서명·origin·challenge·rpIdHash·UP/UV·
     * credential id 바인딩을 검증하고 authenticator가 보고한 signCount를
     * {@link AuthenticationResult#newSignCount()}로 반환한다.
     *
     * <p><b>signCount 단조증가(replay) 검사는 하지 않는다.</b> 저장된
     * 이전 signCount와의 비교·갱신은 호출자(앱)의 책임이다. verifier는
     * 새 signCount를 추출해 줄 뿐 거부하지 않는다.
     */
    AuthenticationResult verifyAuthentication(AuthenticationInput input)
            throws WebAuthnVerificationException;
}
