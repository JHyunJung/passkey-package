package com.crosscert.passkey.admin.mds

import java.time.Instant

data class MdsHistoryView(
    val id: Long,
    val startedAt: String?,
    val finishedAt: String?,
    val version: Long?,
    val status: String?,
    val changeSummary: String?,
    val durationMs: Int?,
    val errorMessage: String?,
) {
    companion object {
        fun of(
            id: Long,
            startedAt: Instant?,
            finishedAt: Instant?,
            version: Long?,
            status: String?,
            changeSummary: String?,
            durationMs: Int?,
            errorMessage: String?,
        ): MdsHistoryView = MdsHistoryView(
            id,
            startedAt?.toString(),
            finishedAt?.toString(),
            version, status, changeSummary, durationMs, errorMessage,
        )
    }
}
