package com.crosscert.passkey.webauthn.mds;

/** MDS3 BLOB 검증·파싱 실패. */
public final class MdsException extends Exception {

    public enum Reason { MALFORMED_JWS, BAD_SIGNATURE, UNTRUSTED_CHAIN, MALFORMED_PAYLOAD }

    private final Reason reason;

    public MdsException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public MdsException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
