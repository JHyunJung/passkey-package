package com.crosscert.passkey.webauthn.verifier;

/** WebAuthn 검증 실패. reason은 앱이 ErrorCode로 매핑한다. */
public final class WebAuthnVerificationException extends Exception {

    public enum Reason {
        MALFORMED_INPUT,
        BAD_SIGNATURE,
        ORIGIN_MISMATCH,
        CHALLENGE_MISMATCH,
        TYPE_MISMATCH,
        RP_ID_HASH_MISMATCH,
        UP_REQUIRED,
        UV_REQUIRED,
        UNSUPPORTED_ALGORITHM,
        UNSUPPORTED_ATTESTATION_FORMAT,
        ATTESTATION_FORMAT_NOT_ACCEPTED,
        ATTESTATION_UNTRUSTED
    }

    private final Reason reason;

    public WebAuthnVerificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WebAuthnVerificationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
