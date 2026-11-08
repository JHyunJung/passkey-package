package com.crosscert.passkey.admin.operator

import java.time.OffsetDateTime
import java.util.UUID

class AdminUserDto private constructor() {

    data class View(
        val id: UUID?,
        val email: String?,
        val role: String?,
        val status: String?,
        val tenantIds: List<UUID>?,
        val createdAt: OffsetDateTime?,
        val lastLoginAt: OffsetDateTime?,
        val suspendedAt: OffsetDateTime?,
        val createdBy: String?,
        val mfaEnabled: Boolean,
    )
}
