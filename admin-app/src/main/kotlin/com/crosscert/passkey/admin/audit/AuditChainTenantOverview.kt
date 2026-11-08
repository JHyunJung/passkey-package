package com.crosscert.passkey.admin.audit

import java.util.UUID

data class AuditChainTenantOverview(
    val tenantId: UUID?,
    val tenantName: String?,
    val intact: Boolean,
    val verifiedRows: Long,
    val buckets: List<Long>?,
    val tamperedEntryId: UUID?,
)
