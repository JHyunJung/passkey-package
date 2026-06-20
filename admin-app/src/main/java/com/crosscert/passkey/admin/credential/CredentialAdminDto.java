package com.crosscert.passkey.admin.credential;

import java.time.OffsetDateTime;

public final class CredentialAdminDto {

    private CredentialAdminDto() {}

    public record CredentialView(
            String  credentialId,        // base64url, no padding
            String  userHandle,          // base64url, no padding
            String  label,               // 사용자가 self-service 로 붙인 별칭. null 가능
            String  aaguidHex,           // 32-char hex (HexFormat.formatHex). null 가능
            String  authenticatorName,   // MdsAaguidCache 룩업 결과. 없으면 null
            String  attestationFormat,
            String  transports,
            long    signCount,
            OffsetDateTime lastUsedAt,   // null 가능
            OffsetDateTime createdAt
    ) {}

    public record AuthEventView(
            String result,
            String failureReason,
            long signCount,
            OffsetDateTime createdAt) {}
}
