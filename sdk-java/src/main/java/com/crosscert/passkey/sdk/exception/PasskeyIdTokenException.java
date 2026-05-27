package com.crosscert.passkey.sdk.exception;

public class PasskeyIdTokenException extends RuntimeException {
    public PasskeyIdTokenException(String message) { super(message); }
    public PasskeyIdTokenException(String message, Throwable cause) { super(message, cause); }
}
