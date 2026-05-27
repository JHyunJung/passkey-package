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
        List<AuditLog> feed = actionFilter.isEmpty()
                ? activity.feed(sinceId, FEED_PAGE)
                : activity.feedFiltered(actionFilter, sinceId, FEED_PAGE);

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

    private String categorize(String action) {
        if (OPS_ACTIONS.contains(action))      return "ops";
        if (SECURITY_ACTIONS.contains(action)) return "security";
        return "system";
    }
}
