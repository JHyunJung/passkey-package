package com.crosscert.passkey.admin.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload for GET /admin/api/activity.
 *
 * <p>kpi.p95Ms is intentionally {@code Long} (nullable) — null is rendered
 * as 'N/A' in the UI until Micrometer instrumentation is wired in a later
 * phase. The other three KPIs are always non-null counts.
 *
 * <p>top5 is at most 5 tenants ordered by 24h event count desc.
 * Rows with {@code tenant_id IS NULL} (ADMIN_LOGIN, signing-key, MDS sync)
 * are excluded from top5 by the repository query.
 *
 * <p>feed.category is one of: {@code "ops"} / {@code "security"} / {@code "system"}.
 */
public record ActivityView(
        Kpi kpi,
        List<TopTenant> top5,
        List<Event> feed
) {
    public record Kpi(
            long events24h,
            long ops24h,
            long security24h,
            Long p95Ms
    ) {}

    public record TopTenant(
            UUID tenantId,
            String slug,
            long count
    ) {}

    public record Event(
            UUID id,
            String action,
            String actorEmail,
            String targetType,
            String targetId,
            UUID tenantId,
            String tenantSlug,
            Instant createdAt,
            String category
    ) {}
}
