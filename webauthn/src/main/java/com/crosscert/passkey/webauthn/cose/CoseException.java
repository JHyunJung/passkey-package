package com.crosscert.passkey.webauthn.cose;

/** COSE_Key 파싱·알고리즘 매핑 실패. */
public final class CoseException extends RuntimeException {
    public CoseException(String message) { super(message); }
    public CoseException(String message, Throwable cause) { super(message, cause); }
}
