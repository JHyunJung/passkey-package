package com.crosscert.passkey.webauthn.clientdata;

/** clientDataJSON 파싱·검증 실패. reason은 호출부가 상위 예외로 매핑. */
public final class ClientDataException extends RuntimeException {
    public enum Reason { MALFORMED, TYPE_MISMATCH, CHALLENGE_MISMATCH, ORIGIN_MISMATCH }
    private final Reason reason;
    public ClientDataException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
