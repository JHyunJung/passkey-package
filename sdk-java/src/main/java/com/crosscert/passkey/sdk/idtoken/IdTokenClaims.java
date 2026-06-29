package com.crosscert.passkey.sdk.idtoken;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record IdTokenClaims(
    @JsonProperty("iss") String iss,
    @JsonProperty("sub") String sub,
    @JsonProperty("aud") String aud,
    @JsonProperty("iat") Instant iat,
    @JsonProperty("exp") Instant exp,
    @JsonProperty("amr") List<String> amr,
    @JsonProperty("credId") String credId,
    @JsonProperty("aaguid") String aaguid
) {}
