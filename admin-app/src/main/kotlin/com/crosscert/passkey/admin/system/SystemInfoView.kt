package com.crosscert.passkey.admin.system

data class SystemInfoView(
    val serverVersion: String?,
    /** 빌드 시각(KST, OffsetDateTime.toString) — build-info 가 없으면 null 이고 UI 는 "—" 로 표시한다. */
    val deployedAt: String?,
    val uptimeDays: Long?,
    val host: Host?,
    val components: List<Component>?,
) {
    data class Host(
        /** 활성 Spring 프로필을 쉼표로 이은 값 — 없으면 "default". */
        val environment: String?,
    )

    data class Component(
        val name: String?,
        val version: String?,
        val status: String?,
        val note: String?,
    )
}
