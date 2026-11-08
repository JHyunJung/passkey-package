package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.core.entity.TenantAaguidPolicy
import java.time.OffsetDateTime
import java.util.UUID

class AaguidPolicyDto private constructor() {

    data class Entry(val aaguid: UUID?, val note: String?, val mdsName: String?)

    data class View(
        val tenantId: UUID?,
        val mode: TenantAaguidPolicy.Mode?,
        val mdsStrict: Boolean,
        val entries: List<Entry>,
        val updatedAt: OffsetDateTime?,
        val updatedBy: String?,
    )

    data class UpdateRequest(
        val mode: TenantAaguidPolicy.Mode?,
        val mdsStrict: Boolean,
        val entries: List<EntryInput>?,
    )

    data class EntryInput(val aaguid: UUID?, val note: String?)
}
