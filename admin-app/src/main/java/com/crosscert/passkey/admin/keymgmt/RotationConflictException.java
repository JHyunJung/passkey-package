package com.crosscert.passkey.admin.keymgmt;

/**
 * Thrown by {@link KeyRotationService#rotate} when the
 * "key-rotation" lease is held by another instance / concurrent click.
 *
 * <p>Dedicated subclass (not raw {@code IllegalStateException}) so the
 * T19 controller layer can map THIS specific failure to HTTP 409
 * without false-positive 409s for other internal {@code IllegalStateException}s
 * (no ACTIVE key, key-gen failure, envelope failure, audit-hash failure).
 *
 * <p>Extends {@code IllegalStateException} so existing handlers that
 * treat ISE as 4xx still get correct behaviour, while new handlers can
 * pattern-match on this specific type for the explicit 409 path.
 */
public class RotationConflictException extends IllegalStateException {
    public RotationConflictException(String message) {
        super(message);
    }
}
