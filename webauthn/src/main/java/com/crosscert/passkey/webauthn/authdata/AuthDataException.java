package com.crosscert.passkey.webauthn.authdata;

/** authenticatorData 파싱 실패. */
public final class AuthDataException extends RuntimeException {
    public AuthDataException(String message) { super(message); }
}
