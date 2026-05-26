package com.crosscert.passkey.core.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    // Common (C)
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "Invalid input value"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "Method not allowed"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "C003", "Entity not found"),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "C004", "Required parameter missing"),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "C005", "Type mismatch"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "Server error"),

    // Auth (A)
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "Authentication required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A002", "Access denied"),

    // Tenant (T)
    TENANT_NOT_FOUND(HttpStatus.NOT_FOUND, "T001", "Tenant not found"),
    TENANT_DUPLICATE(HttpStatus.CONFLICT, "T002", "Tenant code already exists"),

    // API Key (K)
    API_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "K001", "API key not found"),

    // Signing Key (S)
    KEY_ROTATION_CONFLICT(HttpStatus.CONFLICT, "S001", "Another rotation in progress"),
    KEY_NO_ACTIVE(HttpStatus.INTERNAL_SERVER_ERROR, "S002", "No ACTIVE signing key"),

    // MDS (M)
    MDS_SYNC_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "M001", "MDS BLOB sync failed"),

    // FIDO2 / WebAuthn (F)
    CHALLENGE_INVALID(HttpStatus.BAD_REQUEST, "F001", "Challenge invalid or expired"),
    REGISTRATION_FAILED(HttpStatus.BAD_REQUEST, "F002", "Registration verification failed"),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "F003", "Authentication verification failed"),
    ATTESTATION_REJECTED(HttpStatus.FORBIDDEN, "F004", "Attestation rejected by policy"),
    AAGUID_REVOKED(HttpStatus.FORBIDDEN, "F005", "Authenticator revoked");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status; this.code = code; this.message = message;
    }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
