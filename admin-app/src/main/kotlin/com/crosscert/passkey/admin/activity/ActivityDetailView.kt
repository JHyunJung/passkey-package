package com.crosscert.passkey.admin.activity

import com.crosscert.passkey.core.entity.AuditLog
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 단건 Activity 상세 — payload 포함. 폴링 피드([ActivityView.Event])에는
 * CLOB payload 를 싣지 않고, 행 클릭 시 GET /admin/api/activity/{id} 로만 조회한다.
 *
 * `tenantSlug` 와 `category` 는 엔티티에 없는 파생 값이라 서비스가
 * 주입한다. [from] 는 엔티티 직역 필드만 채우고 그 둘은 인자로 받는다.
 */
data class ActivityDetailView(
    val id: UUID?,
    val action: String?,
    val actorId: UUID?,
    val actorEmail: String?,
    val targetType: String?,
    val targetId: String?,
    val tenantId: UUID?,
    val tenantSlug: String?,
    val createdAt: OffsetDateTime?,
    val category: String?,
    val payload: String?,
) {
    companion object {
        fun from(a: AuditLog, tenantSlug: String?, category: String?): ActivityDetailView = ActivityDetailView(
            a.id,
            a.action,
            a.actorId,
            a.actorEmail,
            a.targetType,
            a.targetId,
            a.tenantId,
            tenantSlug,
            a.createdAt,
            category,
            a.payload,
        )
    }
}
