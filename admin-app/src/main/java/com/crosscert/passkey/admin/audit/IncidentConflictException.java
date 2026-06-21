package com.crosscert.passkey.admin.audit;

/** 테넌트에 이미 OPEN incident 가 있을 때. → HTTP 409. */
public class IncidentConflictException extends RuntimeException {
    public IncidentConflictException(String message) { super(message); }
}
