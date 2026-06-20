package com.crosscert.passkey.app.api.v1.rp.dto;

import java.time.OffsetDateTime;

public record CredentialView(
        String credentialId,   // base64url
        String label,
        String aaguidHex,
        OffsetDateTime lastUsedAt
) {}
