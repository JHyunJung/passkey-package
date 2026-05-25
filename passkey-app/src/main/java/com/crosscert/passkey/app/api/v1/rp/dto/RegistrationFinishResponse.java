package com.crosscert.passkey.app.api.v1.rp.dto;

import java.time.Instant;

public record RegistrationFinishResponse(
        String credentialId,
        String aaguid,
        String attestationFormat,
        Instant createdAt
) {}
