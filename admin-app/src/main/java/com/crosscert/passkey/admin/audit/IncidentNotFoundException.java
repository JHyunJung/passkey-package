package com.crosscert.passkey.admin.audit;

/** 주어진 id 의 incident 가 존재하지 않을 때. → HTTP 404. */
public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException(String message) { super(message); }
}
