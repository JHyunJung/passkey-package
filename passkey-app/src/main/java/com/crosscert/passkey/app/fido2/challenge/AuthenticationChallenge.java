package com.crosscert.passkey.app.fido2.challenge;

import java.time.Instant;

public record AuthenticationChallenge(
        String tenantId,
        byte[] challenge,
        byte[] userHandle, // nullable for usernameless flows
        Instant issuedAt
) {}
