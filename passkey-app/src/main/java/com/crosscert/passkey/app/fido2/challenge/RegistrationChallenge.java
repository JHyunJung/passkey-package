package com.crosscert.passkey.app.fido2.challenge;

import java.time.Instant;

public record RegistrationChallenge(
        String tenantId,
        byte[] challenge,
        byte[] userHandle,
        String displayName,
        String username,
        Instant issuedAt
) {}
