package com.crosscert.passkey.admin.audit

import java.time.Instant

data class AuditChainOverview(
    val verifiedAt: Instant?,
    val windowHours: Int,
    val bucketSizeMinutes: Int,
    val totals: Totals?,
    val tenants: List<AuditChainTenantOverview>?,
) {
    data class Totals(
        val tenantsIntact: Int,
        val tenantsTotal: Int,
        val tenantsTampered: Int,
        val verifiedRows: Long,
        val verificationMs: Long,
    )
}
