package com.crosscert.passkey.webauthn.verifier;

import java.util.Set;

public record AuthenticationInput(
        String credentialJson,
        byte[] challenge,
        Set<String> allowedOrigins,
        String rpId,
        boolean userVerificationRequired,
        StoredCredential storedCredential
) {}
