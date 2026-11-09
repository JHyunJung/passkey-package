package com.crosscert.passkey.admin.config

import java.util.UUID

data class MeView(
    val email: String?,
    val role: String?,
    val tenantIds: List<UUID>?,
    val mfaEnabled: Boolean,
    val mfaRequired: Boolean,
    val sessionIdleTimeoutMinutes: Int,
)
