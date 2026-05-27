package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/api/audit/chain")
public class AuditChainMonitorController {

    private final AuditChainVerifier verifier;
    private final AuditChainBackfillService backfillService;
    private final AuditLogRepository auditRepo;
    private final TenantRepository tenantRepo;
    private final Clock clock;

    public AuditChainMonitorController(AuditChainVerifier verifier,
                                       AuditChainBackfillService backfillService,
                                       AuditLogRepository auditRepo,
                                       TenantRepository tenantRepo,
                                       Clock clock) {
        this.verifier = verifier;
        this.backfillService = backfillService;
        this.auditRepo = auditRepo;
        this.tenantRepo = tenantRepo;
        this.clock = clock;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public AuditChainOverview overview(@RequestParam(defaultValue = "24") int windowHours) {
        long startMs = System.currentTimeMillis();
        Instant now = clock.instant();
        Instant since = now.minus(windowHours, ChronoUnit.HOURS);
        int bucketSizeMinutes = 60;

        List<AuditChainVerifier.TenantResult> results = verifier.verifyAllTenants();
        Map<UUID, Tenant> tenantsById = tenantRepo.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, t -> t));

        List<AuditChainTenantOverview> tenants = new ArrayList<>(results.size());
        long verifiedRowsTotal = 0;
        int tampered = 0;
        for (var r : results) {
            List<AuditLog> rows = auditRepo.findAllByTenantOrdered(r.tenantId());
            verifiedRowsTotal += rows.size();
            if (!r.ok()) tampered++;

            long[] buckets = new long[windowHours];
            for (AuditLog row : rows) {
                Instant t = row.getCreatedAt();
                if (t.isBefore(since)) continue;
                long minutesSince = ChronoUnit.MINUTES.between(t, now);
                int idx = (int) (minutesSince / bucketSizeMinutes);
                if (idx >= 0 && idx < windowHours) {
                    buckets[windowHours - 1 - idx]++;
                }
            }
            List<Long> bucketList = new ArrayList<>(windowHours);
            for (long b : buckets) bucketList.add(b);

            String name;
            if (r.tenantId() == null) {
                name = "[platform]";
            } else if (tenantsById.containsKey(r.tenantId())) {
                name = tenantsById.get(r.tenantId()).getDisplayName();
            } else {
                name = r.tenantId().toString();
            }
            tenants.add(new AuditChainTenantOverview(
                    r.tenantId(), name, r.ok(), rows.size(), bucketList, r.brokenAt()));
        }

        long verifyMs = System.currentTimeMillis() - startMs;
        AuditChainOverview.Totals totals = new AuditChainOverview.Totals(
                results.size() - tampered, results.size(), tampered, verifiedRowsTotal, verifyMs);
        return new AuditChainOverview(now, windowHours, bucketSizeMinutes, totals, tenants);
    }

    public record TenantVerifyResponse(UUID tenantId, boolean intact, UUID tamperedEntryId, Instant verifiedAt) {}

    @GetMapping("/verify")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public TenantVerifyResponse verifyTenant(@RequestParam(required = false) String tenantId) {
        UUID resolved;
        if (tenantId == null || tenantId.isBlank() || "null".equalsIgnoreCase(tenantId)) {
            resolved = null;  // platform chain
        } else {
            resolved = UUID.fromString(tenantId);
        }
        var r = verifier.verifyTenant(resolved);
        return new TenantVerifyResponse(r.tenantId(), r.ok(), r.brokenAt(), clock.instant());
    }

    public record BackfillResponse(int tenantsProcessed, int rowsUpdated, int rowsSkipped) {}

    @PostMapping("/backfill")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public BackfillResponse backfill() {
        var s = backfillService.backfill();
        return new BackfillResponse(s.tenantsProcessed(), s.rowsUpdated(), s.rowsSkipped());
    }
}
