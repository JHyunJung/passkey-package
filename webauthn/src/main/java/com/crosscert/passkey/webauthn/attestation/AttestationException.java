package com.crosscert.passkey.webauthn.attestation;

/** attestation statement 검증 실패. */
public final class AttestationException extends RuntimeException {
    public AttestationException(String message) { super(message); }
    public AttestationException(String message, Throwable cause) { super(message, cause); }
}
