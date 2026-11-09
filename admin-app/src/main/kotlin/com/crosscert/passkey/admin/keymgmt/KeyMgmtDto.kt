package com.crosscert.passkey.admin.keymgmt

import com.crosscert.passkey.core.entity.SigningKey
import java.time.OffsetDateTime
import java.util.UUID

class KeyMgmtDto private constructor() {

    data class SigningKeyView(
        val id: UUID?,
        val kid: String?,
        val alg: String?,
        val status: String?,
        val createdAt: OffsetDateTime?,
        val rotatedAt: OffsetDateTime?,
        val revokedAt: OffsetDateTime?,
    ) {
        companion object {
            fun from(k: SigningKey): SigningKeyView = SigningKeyView(
                k.id, k.kid, k.alg, k.status,
                k.createdAt, k.rotatedAt, k.revokedAt,
            )
        }
    }

    data class RotateResponse(val oldKid: String?, val newKid: String?)

    data class KeyList(val keys: List<SigningKeyView>?)
}
