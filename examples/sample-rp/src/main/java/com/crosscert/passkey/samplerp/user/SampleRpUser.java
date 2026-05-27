package com.crosscert.passkey.samplerp.user;

import java.io.Serializable;
import java.time.Instant;

public record SampleRpUser(
        String userHandle,
        String username,
        String displayName,
        Instant createdAt,
        String credentialId   // confirmRegistration 후 채워짐. 없으면 null (pending).
) implements Serializable {}
