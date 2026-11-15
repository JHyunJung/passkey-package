package com.crosscert.passkey.sdk.idtoken;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * 검증을 통과한 ID Token 의 claim. {@code sub} 는 등록 때 넘긴 userHandle 바이트의 base64url(패딩 없음),
 * {@code iss} 는 {@code <issuerBase>/<tenantId>}, {@code aud} 는 tenantId(여러 개면 첫 값)다.
 * {@code amr} 은 없으면 빈 목록, {@code credId}·{@code aaguid} 는 토큰의 {@code cred_id}·{@code aaguid}
 * claim 이며 없으면 null 이다.
 */
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
