package com.crosscert.passkey.admin.activity

import com.crosscert.passkey.core.api.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@RequestMapping("/admin/api/activity")
class ActivityController(private val service: ActivityService) {

    /**
     * PLATFORM_OPERATOR 전용. RP_ADMIN 은 TenantDetail 의 Activity 탭에서
     * /admin/api/audit?tenantId={self} 를 사용 — admin-role-separation 의
     * scope check 가 자기 tenant 강제.
     *
     * Phase F5 — added optional `before` (backward pagination) and
     * `tenantId` (tenant-scoped feed) params. KPIs / top5 always stay on
     * the latest 24h global window; only `feed` responds to these
     * cursors. `sinceId` (forward polling) and `before` (backward
     * pagination) are mutually exclusive — when both are supplied,
     * `sinceId` wins (matches dashboard polling contract).
     *
     * F07 — added optional `beforeId`, the `id` of the last row
     * from the previous page (i.e. `feed[feed.length-1].id`). When
     * supplied alongside `before`, backward pagination uses the
     * `(createdAt, id)` tuple cursor instead of a strict-instant
     * comparison, so a row sharing the exact microsecond timestamp as the
     * cursor is not permanently dropped. Optional for clients that only send
     * `before` (backward compatible, keeps the prior approximate
     * behavior).
     */
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    fun activity(
        @RequestParam(required = false) sinceId: UUID?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) before: OffsetDateTime?,
        @RequestParam(required = false) beforeId: UUID?,
        @RequestParam(required = false) tenantId: UUID?,
    ): ApiResponse<ActivityView> =
        ApiResponse.ok(service.snapshot(sinceId, category, before, beforeId, tenantId))

    /** 행 클릭 시 단건 상세 — payload 포함. PLATFORM_OPERATOR 전용(피드와 동일 경계). */
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/{id}")
    fun detail(@PathVariable id: UUID): ApiResponse<ActivityDetailView> = ApiResponse.ok(service.detail(id))
}
