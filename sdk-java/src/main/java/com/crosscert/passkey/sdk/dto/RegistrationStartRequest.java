package com.crosscert.passkey.sdk.dto;

public record RegistrationStartRequest(
        String userHandle,
        String displayName,
        String username
) {}
