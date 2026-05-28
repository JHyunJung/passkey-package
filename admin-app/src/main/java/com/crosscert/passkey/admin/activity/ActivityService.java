package com.crosscert.passkey.admin.activity;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ActivityRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the snapshot served by GET /admin/api/activity.
 *
 * <p>OPS_ACTIONS / SECURITY_ACTIONS are closed sets of action constants. Any
 * action not in either set is categorized as {@code "system"} (e.g.,
 * SIGNING_KEY_ROTATE, MDS_BLOB_SYNC).
 */
@Service
public class ActivityService {

    static final Set<String> OPS_ACTIONS = Set.of(
            "TENANT_CREATE", "TENANT_UPDATE",
            "CREDENTIAL_REVOKE",
            "API_KEY_ISSUE", "API_KEY_REVOKE",
            "SIGNING_KEY_ROTATE",
            "ADMIN_LOGIN");

    static final Set<String> SECURITY_ACTIONS = Set.of(
            "ADMIN_LOGIN_FAILED");

    private static final int TOP_N = 5;
    private static final int FEED_PAGE = 50;
    private static final Duration WINDOW = Duration.ofHours(24);

    private final ActivityRepository activity;
    private final TenantRepository tenants;
    private final Clock clock;

    public ActivityService(ActivityRepository activity,
                            TenantRepository tenants,
                            Clock clock) {
        this.activity = activity;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ActivityView snapshot(UUID sinceId, String category) {
        return snapshot(sinceId, category, null, null);
    }

    /**
     * Phase F5 — extended snapshot with optional backward pagination ({@code before})
     * and tenant scoping ({@code tenantId}).
     *
     * <p><b>Important:</b> KPIs ({@code events24h}, {@code ops24h}, {@code security24h})
     * and {@code top5} are intentionally NOT shifted by {@code before} or {@code tenantId}.
     * They always reflect the latest 24h global window — the dashboard's headline
     * counters stay stable as the user scrolls back through the feed or narrows
     * to a single tenant. Only the {@code feed} is affected by the new filters.
     *
     * <p>Cursor semantics:
     * <ul>
     *   <li>{@code sinceId != null}: forward polling — rows newer than the cursor row.
     *       {@code before} is ignored when {@code sinceId} is supplied (mutually
     *       exclusive — forward vs. backward).</li>
     *   <li>{@code before != null} (and {@code sinceId == null}): backward pagination —
     *       rows strictly older than the supplied instant.</li>
     *   <li>{@code tenantId != null}: feed restricted to that tenant. Composable with
     *       either {@code sinceId} (forward polling, tenant-scoped) or {@code before}
     *       (backward pagination, tenant-scoped) — or neither (newest 50 for that
     *       tenant).</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public ActivityView snapshot(UUID sinceId, String category, Instant before, UUID tenantId) {
        Instant since = clock.instant().minus(WINDOW);

        long events24h    = activity.countSince(since);
        long ops24h       = activity.countByActionsSince(OPS_ACTIONS, since);
        long security24h  = activity.countByActionsSince(SECURITY_ACTIONS, since);

        List<ActivityView.TopTenant> top5 = activity.topTenantsSince(since, TOP_N)
                .stream()
                .map(row -> new ActivityView.TopTenant(
                        row.tenantId(),
                        tenants.findById(row.tenantId())
                                .map(Tenant::getSlug)
                                .orElse("(deleted)"),
                        row.count()))
                .toList();

        Set<String> actionFilter = switch (category == null ? "all" : category) {
            case "ops"      -> OPS_ACTIONS;
            case "security" -> SECURITY_ACTIONS;
            default         -> Set.of();
        };
        List<AuditLog> feed = resolveFeed(sinceId, before, tenantId, actionFilter);

        // distinct().toMap() avoids N+1 — 1 query per unique tenant in the page.
        Map<UUID, String> slugByTenant = feed.stream()
                .map(AuditLog::getTenantId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(
                        Function.identity(),
                        tid -> tenants.findById(tid).map(Tenant::getSlug).orElse("(deleted)")));

        List<ActivityView.Event> events = feed.stream()
                .map(a -> new ActivityView.Event(
                        a.getId(),
                        a.getAction(),
                        a.getActorEmail(),
                        a.getTargetType(),
                        a.getTargetId(),
                        a.getTenantId(),
                        a.getTenantId() == null ? null : slugByTenant.get(a.getTenantId()),
                        a.getCreatedAt(),
                        categorize(a.getAction())))
                .toList();

        return new ActivityView(
                new ActivityView.Kpi(events24h, ops24h, security24h, null),
                top5, events);
    }

    /**
     * Phase F5 — pick the right repository entry point based on which cursor /
     * filter the caller supplied. Centralised here so the controller-facing
     * {@link #snapshot} reads as a flat data-pipeline.
     *
     * <p>{@code sinceId} (forward polling) takes precedence over {@code before}
     * (backward pagination). They are not designed to be combined — a polling
     * client asks "what's new since X", a pagination client asks "what came
     * before Y". The dashboard never sends both.
     */
    private List<AuditLog> resolveFeed(UUID sinceId, Instant before, UUID tenantId,
                                       Set<String> actionFilter) {
        if (sinceId != null) {
            // Forward polling path. tenantId composes — null means global,
            // non-null restricts polling to a single tenant (e.g. TenantDetail's
            // Activity tab calling /admin/api/activity?sinceId=X&tenantId=Y).
            return actionFilter.isEmpty()
                    ? activity.feed(sinceId, tenantId, FEED_PAGE)
                    : activity.feedFiltered(actionFilter, sinceId, tenantId, FEED_PAGE);
        }
        // No sinceId → use the (before, tenantId) page path. Both may be null,
        // in which case feedPage returns the newest FEED_PAGE rows globally
        // (equivalent to feed(null, FEED_PAGE) but expressed via the new query).
        return actionFilter.isEmpty()
                ? activity.feedPage(tenantId, before, FEED_PAGE)
                : activity.feedFilteredPage(actionFilter, tenantId, before, FEED_PAGE);
    }

    private String categorize(String action) {
        if (OPS_ACTIONS.contains(action))      return "ops";
        if (SECURITY_ACTIONS.contains(action)) return "security";
        return "system";
    }
}
