package com.crosscert.passkey.admin.mds

data class MdsStatusView(
    val version: Long,
    val nextUpdate: String?,
    val fetchedAt: String?,
    val trustAnchorCount: Int,
    val trustMode: String?,
    val successRate30d: SuccessRate?,
) {
    data class SuccessRate(val ok: Int, val total: Int)
}
