package com.crosscert.passkey.admin.credential;

import java.time.Instant;

public final class CredentialAdminDto {

    private CredentialAdminDto() {}

    public record CredentialView(
            String  credentialId,        // base64url, no padding
            String  userHandle,          // base64url, no padding
            String  aaguidHex,           // 32-char hex (HexFormat.formatHex). null 가능
            String  authenticatorName,   // MdsAaguidCache 룩업 결과. 없으면 null
            String  attestationFormat,
            String  transports,
            long    signCount,
            Instant lastUsedAt,          // null 가능
            Instant createdAt
    ) {}
}
