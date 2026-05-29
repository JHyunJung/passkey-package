package com.crosscert.passkey.core.license;

public class LicenseVerificationException extends RuntimeException {
    public LicenseVerificationException(String message) { super(message); }
    public LicenseVerificationException(String message, Throwable cause) { super(message, cause); }
}
