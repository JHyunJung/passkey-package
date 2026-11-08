package com.crosscert.passkey.admin.activity

import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.repository.ActivityRepository
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.TenantRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.stream.Collectors

/*
 * OPS_ACTIONS / SECURITY_ACTIONS 는 java.util.Set.of 로 둔다 — 원 Java 와 같은 불변 집합이며
 * contains(null) 이 NPE 인 동작까지 동일하게 유지한다(Kotlin setOf 는 false 를 반환).
 */
private val OPS_ACTIONS: Set<String> = java.util.Set.of(
    "TENANT_CREATE", "TENANT_UPDATE",
    "CREDENTIAL_REVOKE",
    "API_KEY_ISSUE", "API_KEY_REVOKE",
    "SIGNING_KEY_ROTATE",
    "ADMIN_LOGIN",
)

private val SECURITY_ACTIONS: Set<String> = java.util.Set.of(
    "ADMIN_LOGIN_FAILED",
)

private const val TOP_N = 5
private const val FEED_PAGE = 50
private val WINDOW: Duration = Duration.ofHours(24)

/**
 * Builds the snapshot served by GET /admin/api/activity.
 *
 * OPS_ACTIONS / SECURITY_ACTIONS are closed sets of action constants. Any
 * action not in either set is categorized as `"system"` (e.g.,
 * SIGNING_KEY_ROTATE, MDS_BLOB_SYNC).
 */
@Service
class ActivityService(
    private val activity: ActivityRepository,
    private val tenants: TenantRepository,
    private val clock: Clock,
    private val auditLogs: AuditLogRepository,
) {

    @Transactional(readOnly = true)
    fun snapshot(sinceId: UUID?, category: String?): ActivityView =
        snapshot(sinceId, category, null, null, null)

    /**
     * Phase F5 — extended snapshot with optional backward pagination (`before`)
     * and tenant scoping (`tenantId`).
     *
     * **Important:** KPIs (`events24h`, `ops24h`, `security24h`)
     * and `top5` are intentionally NOT shifted by `before` or `tenantId`.
     * They always reflect the latest 24h global window — the dashboard's headline
     * counters stay stable as the user scrolls back through the feed or narrows
     * to a single tenant. Only the `feed` is affected by the new filters.
     *
     * Cursor semantics:
     * - `sinceId != null`: forward polling — rows newer than the cursor row.
     *   `before` is ignored when `sinceId` is supplied (mutually
     *   exclusive — forward vs. backward).
     * - `before != null` (and `sinceId == null`): backward pagination —
     *   rows strictly older than the supplied instant.
     * - `tenantId != null`: feed restricted to that tenant. Composable with
     *   either `sinceId` (forward polling, tenant-scoped) or `before`
     *   (backward pagination, tenant-scoped) — or neither (newest 50 for that
     *   tenant).
     *
     * F07 — `beforeId` (optional) is the `id` of the last row
     * from the previous page (i.e. `events[events.size()-1].id`). When
     * supplied, backward pagination compares the `(createdAt, id)` tuple
     * of that row instead of the strict `createdAt < before` check —
     * mirroring the `sinceId` forward-polling cursor — so a boundary row
     * sharing the exact same microsecond timestamp as the cursor is not
     * permanently dropped. `beforeId` is optional for backward
     * compatibility with callers that only have `before`.
     */
    @Transactional(readOnly = true)
    fun snapshot(
        sinceId: UUID?,
        category: String?,
        before: OffsetDateTime?,
        beforeId: UUID?,
        tenantId: UUID?,
    ): ActivityView {
        val since = OffsetDateTime.now(clock).minus(WINDOW)

        val events24h = activity.countSince(since)
        val ops24h = activity.countByActionsSince(OPS_ACTIONS, since)
        val security24h = activity.countByActionsSince(SECURITY_ACTIONS, since)

        val top5 = activity.topTenantsSince(since, TOP_N)
            .map { row ->
                ActivityView.TopTenant(
                    row.tenantId(),
                    tenants.findById(row.tenantId())
                        .map { it.slug }
                        .orElse("(deleted)"),
                    row.count(),
                )
            }

        val actionFilter: Set<String> = when (category ?: "all") {
            "ops" -> OPS_ACTIONS
            "security" -> SECURITY_ACTIONS
            else -> java.util.Set.of()
        }
        val feed = resolveFeed(sinceId, before, beforeId, tenantId, actionFilter)

        // 페이지에 등장한 distinct tenantId 들을 한 번의 IN 쿼리(findAllById)로 로드 —
        // incident 의 buildLookups 와 동일한 배치 패턴. per-tenant findById N+1 제거.
        // 주의: findAllById 는 존재하지 않는 ID(삭제된 테넌트)를 결과에 넣지 않으므로,
        // 아래 slug 조회에서 getOrDefault(..., "(deleted)") fallback 으로 표기를 보존한다.
        val tenantIds: Set<UUID> = feed.mapNotNullTo(HashSet()) { it.tenantId }
        // Collectors.toMap 유지 — 중복 키·null slug 시 예외인 원 동작 그대로.
        val slugByTenant: Map<UUID, String> = tenants.findAllById(tenantIds).stream()
            .collect(Collectors.toMap(Tenant::getId, Tenant::getSlug))

        val events = feed.map { a ->
            ActivityView.Event(
                a.id,
                a.action,
                a.actorEmail,
                a.targetType,
                a.targetId,
                a.tenantId,
                if (a.tenantId == null) null else slugByTenant.getOrDefault(a.tenantId, "(deleted)"),
                a.createdAt,
                categorize(a.action),
            )
        }

        return ActivityView(
            ActivityView.Kpi(events24h, ops24h, security24h, null),
            top5,
            events,
        )
    }

    /**
     * Phase F5 — pick the right repository entry point based on which cursor /
     * filter the caller supplied. Centralised here so the controller-facing
     * [snapshot] reads as a flat data-pipeline.
     *
     * `sinceId` (forward polling) takes precedence over `before`
     * (backward pagination). They are not designed to be combined — a polling
     * client asks "what's new since X", a pagination client asks "what came
     * before Y". The dashboard never sends both.
     *
     * F07 — `beforeId` (optional) is threaded through to
     * `ActivityRepository#feedPage(UUID, OffsetDateTime, UUID, int)` /
     * `ActivityRepository#feedFilteredPage(Set, UUID, OffsetDateTime, UUID, int)`
     * so backward pagination uses the `(createdAt, id)` tuple cursor
     * instead of the strict-instant comparison.
     */
    private fun resolveFeed(
        sinceId: UUID?,
        before: OffsetDateTime?,
        beforeId: UUID?,
        tenantId: UUID?,
        actionFilter: Set<String>,
    ): List<AuditLog> {
        if (sinceId != null) {
            // Forward polling path. tenantId composes — null means global,
            // non-null restricts polling to a single tenant (e.g. TenantDetail's
            // Activity tab calling /admin/api/activity?sinceId=X&tenantId=Y).
            return if (actionFilter.isEmpty()) {
                activity.feed(sinceId, tenantId, FEED_PAGE)
            } else {
                activity.feedFiltered(actionFilter, sinceId, tenantId, FEED_PAGE)
            }
        }
        // No sinceId → use the (before, beforeId, tenantId) page path. All may be
        // null, in which case feedPage returns the newest FEED_PAGE rows globally
        // (equivalent to feed(null, FEED_PAGE) but expressed via the new query).
        return if (actionFilter.isEmpty()) {
            activity.feedPage(tenantId, before, beforeId, FEED_PAGE)
        } else {
            activity.feedFilteredPage(actionFilter, tenantId, before, beforeId, FEED_PAGE)
        }
    }

    /**
     * 행 클릭 시 단건 상세 — payload 포함. 존재하지 않으면 NOT_FOUND.
     * PLATFORM_OPERATOR 전용 엔드포인트에서만 호출되므로 글로벌 조회(테넌트 무관) 허용.
     */
    @Transactional(readOnly = true)
    fun detail(id: UUID): ActivityDetailView {
        val a = auditLogs.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND, "activity not found: $id") }
        val slug = if (a.tenantId == null) {
            null
        } else {
            tenants.findById(a.tenantId).map { it.slug }.orElse("(deleted)")
        }
        return ActivityDetailView.from(a, slug, categorize(a.action))
    }

    private fun categorize(action: String?): String {
        if (OPS_ACTIONS.contains(action)) return "ops"
        if (SECURITY_ACTIONS.contains(action)) return "security"
        return "system"
    }
}
