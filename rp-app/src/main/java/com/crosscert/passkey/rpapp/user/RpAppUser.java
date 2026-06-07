package com.crosscert.passkey.rpapp.user;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;

public record RpAppUser(
        @JsonProperty("userHandle")  String userHandle,
        @JsonProperty("username")    String username,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("createdAt")   Instant createdAt,
        @JsonProperty("credentialId") String credentialId   // confirmRegistration 후 채워짐. 없으면 null (pending).
) implements Serializable {}
