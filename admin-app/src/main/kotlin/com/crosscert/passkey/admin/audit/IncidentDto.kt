package com.crosscert.passkey.admin.audit

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * audit chain incident 엔드포인트의 요청/응답 DTO 묶음.
 *
 * `AuditChainMonitorController` 는 raw POJO 를 반환한다(ApiResponse 엔벨로프 미사용).
 * [IncidentView] 는 그대로 직렬화돼 프론트로 나간다.
 */
class IncidentDto private constructor() {

    // tamperedEntryId 는 요청에서 받지 않는다 — 서버가 위변조 재검증 결과에서 도출(증거 위조 방지).
    data class CreateRequest(@field:NotBlank val tenantId: String?)

    data class ResolveRequest(@field:NotBlank @field:Size(max = 1024) val note: String?)

    data class IncidentView(
        val id: String?,
        val tenantId: String?,
        val tenantName: String?,
        val tamperedEntryId: String?,
        val type: String?,
        val severity: String?,
        val status: String?,
        val detail: String?,
        val createdAt: String?,
        val createdByEmail: String?,
        val resolvedAt: String?,
        val resolvedByEmail: String?,
        val resolutionNote: String?,
    )
}
