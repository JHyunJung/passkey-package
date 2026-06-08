package com.crosscert.passkey.webauthn.cbor;

/** CBOR 디코드 실패 (잘못된 형식·깊이 초과·잉여 바이트 등). */
public final class CborException extends RuntimeException {
    public CborException(String message) { super(message); }
}
