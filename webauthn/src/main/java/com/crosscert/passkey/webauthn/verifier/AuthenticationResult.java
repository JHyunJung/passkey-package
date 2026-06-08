package com.crosscert.passkey.webauthn.verifier;

/**
 * 인증 검증 결과. newSignCount는 authenticator가 이번 assertion에서 보고한
 * signCount 원본이다. 단조증가(replay) 검사·DB 갱신은 호출자 책임이며,
 * verifier는 이 값을 거부 없이 그대로 전달한다.
 */
public record AuthenticationResult(
        byte[] credentialId,
        long newSignCount,
        boolean uvVerified,
        boolean upVerified,
        boolean backupState
) {}
