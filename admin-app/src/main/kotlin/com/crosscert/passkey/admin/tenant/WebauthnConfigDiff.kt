package com.crosscert.passkey.admin.tenant

data class WebauthnConfigDiff(
    val current: Current?,
    val proposed: Proposed?,
    val changes: List<FieldChange>?,
    val warnings: List<String>?, // RP_ID_CHANGED / ORIGIN_REMOVED / UV_RAISED_TO_REQUIRED / MDS_RAISED_TO_REQUIRED
) {
    data class Current(
        val rpId: String?,
        val rpName: String?,
        val origins: List<String>?,
        val formats: List<String>?,
        val requireUserVerification: Boolean,
        val mdsRequired: Boolean,
    )

    data class Proposed(
        val rpId: String?,
        val rpName: String?,
        val origins: List<String>?,
        val formats: List<String>?,
        val requireUserVerification: Boolean,
        val mdsRequired: Boolean,
    )

    data class FieldChange(
        val field: String?,
        val from: Any?,
        val to: Any?,
        val added: List<String>?,
        val removed: List<String>?,
    )
}
