package com.crosscert.passkey.core.license;

/**
 * Top-level operational state of the license subsystem.
 * Transitions are managed by LicenseStateMachine.
 */
public enum LicenseState {
    /** Within validity window, not yet within warning band. */
    VALID,
    /** Within validity window but expiring within warningDays. */
    WARNING,
    /** Heartbeat failed; running on cached token, grace period not yet exceeded. */
    NETWORK_GRACE,
    /** Expired or grace exceeded — all guarded API calls must be rejected. */
    DEAD
}
