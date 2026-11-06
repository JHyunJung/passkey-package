package com.crosscert.passkey.app.api.v1.rp.dto

import java.time.OffsetDateTime

data class CredentialView(
    val credentialId: String, // base64url
    val label: String?,
    val aaguidHex: String?,
    val lastUsedAt: OffsetDateTime?,
)
