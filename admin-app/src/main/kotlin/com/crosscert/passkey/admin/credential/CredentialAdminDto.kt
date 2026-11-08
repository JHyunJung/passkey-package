package com.crosscert.passkey.admin.credential

import java.time.OffsetDateTime

class CredentialAdminDto private constructor() {

    data class CredentialView(
        val credentialId: String?, // base64url, no padding
        val userHandle: String?, // base64url, no padding
        val label: String?, // 사용자가 self-service 로 붙인 별칭. null 가능
        val aaguidHex: String?, // 32-char hex (HexFormat.formatHex). null 가능
        val authenticatorName: String?, // MdsAaguidCache 룩업 결과. 없으면 null
        val attestationFormat: String?,
        val transports: String?,
        val signCount: Long,
        val lastUsedAt: OffsetDateTime?, // null 가능
        val createdAt: OffsetDateTime?,
    )

    data class AuthEventView(
        val result: String?,
        val failureReason: String?,
        val signCount: Long,
        val createdAt: OffsetDateTime?,
    )
}
