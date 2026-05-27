package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationFinishResponse(
        String credentialId,
        String aaguid,
        String attestationFormat,
        String createdAt
) {}
