package com.crosscert.passkey.app.api.v1.rp.dto;

public record AuthenticationFinishResponse(
        String idToken,
        String tokenType,
        long expiresIn
) {}
