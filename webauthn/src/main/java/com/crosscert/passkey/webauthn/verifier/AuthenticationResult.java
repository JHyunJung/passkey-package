package com.crosscert.passkey.webauthn.verifier;

public record AuthenticationResult(
        byte[] credentialId,
        long newSignCount,
        boolean uvVerified,
        boolean upVerified,
        boolean backupState
) {}
