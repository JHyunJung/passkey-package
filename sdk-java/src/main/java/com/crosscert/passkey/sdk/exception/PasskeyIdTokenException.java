package com.crosscert.passkey.sdk.exception;

/**
 * {@code verifyIdToken} 이 토큰을 받아들이지 않을 때. 형식 오류, RS256 이 아닌 alg, JWKS 에 없는 kid
 * (재조회를 시도한 뒤에도 없을 때), 서명·exp·nbf·iat 실패, JWKS 조회 실패(직전에 받아 둔 키 목록으로
 * 대신할 수 없을 때), 그리고 3-인자 버전의 iss/aud 불일치나 expectedIssuer/expectedAudience 설정 오류가
 * 모두 이 예외다. 이 예외가 나면 로그인을 거부한다.
 */
public class PasskeyIdTokenException extends RuntimeException {
    public PasskeyIdTokenException(String message) {
        super(message);
    }
    public PasskeyIdTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
