package com.crosscert.passkey.webauthn.verifier;

/**
 * 앱과 검증 구현 사이의 유일한 경계. webauthn4j 타입은 이 경계를
 * 절대 넘지 않는다. 구현체: NativeWebAuthnVerifier(프로덕션),
 * Webauthn4jVerifier(differential 테스트 전용).
 */
public interface WebAuthnVerifier {
    RegistrationResult verifyRegistration(RegistrationInput input)
            throws WebAuthnVerificationException;

    AuthenticationResult verifyAuthentication(AuthenticationInput input)
            throws WebAuthnVerificationException;
}
