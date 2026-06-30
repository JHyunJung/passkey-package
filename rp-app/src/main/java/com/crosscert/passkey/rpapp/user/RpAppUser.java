package com.crosscert.passkey.rpapp.user;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;

/** rp-app 이 보관하는 사용자 레코드. username ↔ userHandle ↔ credentialId 매핑의 한 행. credentialId 가 null 이면 등록 미완(pending). */
public record RpAppUser(
        @JsonProperty("userHandle") String userHandle,
        @JsonProperty("username") String username,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("createdAt") Instant createdAt,
        // confirmRegistration 후 채워짐. 없으면 null (pending).
        @JsonProperty("credentialId") String credentialId
) implements Serializable {}
