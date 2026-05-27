package com.crosscert.passkey.sdk.idtoken;

import java.time.Instant;
import java.util.List;

public record IdTokenClaims(
        String iss,
        String sub,
        String aud,
        Instant iat,
        Instant exp,
        List<String> amr,
        String credId,
        String aaguid
) {}
