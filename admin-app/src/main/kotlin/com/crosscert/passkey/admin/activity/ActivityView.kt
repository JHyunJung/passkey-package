package com.crosscert.passkey.admin.activity

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Response payload for GET /admin/api/activity.
 *
 * kpi.p95Ms is intentionally `Long?` (nullable). The other three KPIs are always non-null counts.
 * ActivityService 는 p95Ms 에 항상 null 을 넣고, 화면(ActivityPageController)은 null 이면 그 KPI 카드를 그리지 않는다.
 *
 * top5 is at most 5 tenants ordered by 24h event count desc.
 * Rows with `tenant_id IS NULL` (ADMIN_LOGIN, signing-key, MDS sync)
 * are excluded from top5 by the repository query.
 *
 * feed.category is one of: `"ops"` / `"security"` / `"system"`.
 */
data class ActivityView(
    val kpi: Kpi?,
    val top5: List<TopTenant>?,
    val feed: List<Event>?,
) {
    data class Kpi(
        val events24h: Long,
        val ops24h: Long,
        val security24h: Long,
        val p95Ms: Long?,
    )

    data class TopTenant(
        val tenantId: UUID?,
        val slug: String?,
        val count: Long,
    )

    data class Event(
        val id: UUID?,
        val action: String?,
        val actorEmail: String?,
        val targetType: String?,
        val targetId: String?,
        val tenantId: UUID?,
        val tenantSlug: String?,
        val createdAt: OffsetDateTime?,
        val category: String?,
    )
}
