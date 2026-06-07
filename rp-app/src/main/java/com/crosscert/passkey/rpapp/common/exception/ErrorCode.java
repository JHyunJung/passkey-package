package com.crosscert.passkey.rpapp.common.exception;

import org.springframework.http.HttpStatus;

import static org.springframework.http.HttpStatus.*;

public enum ErrorCode {
    // Common
    INVALID_INPUT       (BAD_REQUEST,             "C001", "Invalid input value"),
    METHOD_NOT_ALLOWED  (HttpStatus.METHOD_NOT_ALLOWED, "C002", "Method not allowed"),
    ENTITY_NOT_FOUND    (NOT_FOUND,               "C003", "Entity not found"),
    MISSING_PARAMETER   (BAD_REQUEST,             "C004", "Required parameter missing"),
    TYPE_MISMATCH       (BAD_REQUEST,             "C005", "Type mismatch"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "Server error"),

    // Auth
    UNAUTHORIZED        (HttpStatus.UNAUTHORIZED, "A001", "Authentication required"),
    ACCESS_DENIED       (FORBIDDEN,               "A002", "Access denied"),

    // WebAuthn flow (rp-app domain)
    USERNAME_TAKEN      (CONFLICT,                "W001", "Username already registered"),
    PENDING_REG_MISSING (BAD_REQUEST,             "W002", "No pending registration in session"),
    PENDING_AUTH_MISSING(BAD_REQUEST,             "W003", "No pending authentication in session"),

    // Passkey-server proxy
    PASSKEY_API_ERROR   (BAD_GATEWAY,             "P001", "Upstream passkey server error"),
    PASSKEY_AUTH_ERROR  (HttpStatus.UNAUTHORIZED, "P002", "Invalid X-API-Key (rp-app config)"),
    PASSKEY_RATE_LIMITED(TOO_MANY_REQUESTS,       "P003", "Upstream rate limit exceeded"),
    PASSKEY_ID_TOKEN    (HttpStatus.UNAUTHORIZED, "P004", "ID Token verification failed");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus()  { return status; }
    public String getCode()        { return code; }
    public String getMessage()     { return message; }
}
