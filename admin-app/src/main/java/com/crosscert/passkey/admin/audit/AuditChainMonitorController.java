package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.entity.SecurityIncident;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final SecurityIncidentService incidents;
    private final AdminUserRepository admins;

    public AuditChainMonitorController(AuditChainVerifier verifier,
                                       AuditChainBackfillService backfillService,
                                       AuditLogRepository auditRepo,
                                       TenantRepository tenantRepo,
                                       Clock clock,
                                       SecurityIncidentService incidents,
                                       AdminUserRepository admins) {
        this.verifier = verifier;
        this.backfillService = backfillService;
        this.auditRepo = auditRepo;
        this.tenantRepo = tenantRepo;
        this.clock = clock;
        this.incidents = incidents;
        this.admins = admins;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public AuditChainOverview overview(@RequestParam(defaultValue = "24") int windowHours) {
        if (windowHours < 1 || windowHours > 168) {
            throw new IllegalArgumentException("windowHours must be between 1 and 168 (1 week)");
        }
        long startMs = clock.millis();
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
                // Window math is purely relative (minutes-between); compare on the
                // absolute instant so the KST offset is irrelevant to bucketing.
                Instant t = row.getCreatedAt().toInstant();
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

        long verifyMs = clock.millis() - startMs;
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

    // ---- Incident 관리 (OPEN → RESOLVED) ----------------------------------

    @GetMapping("/incidents")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public List<IncidentDto.IncidentView> listIncidents() {
        List<SecurityIncident> all = incidents.list();
        // N+1 회피 — 페이지의 모든 tenant/admin id 를 모아 한 번에 배치 로드(overview/ActivityService 패턴).
        ViewLookups lookups = buildLookups(all);
        return all.stream().map(i -> toView(i, lookups)).toList();
    }

    @PostMapping("/incidents")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentDto.IncidentView createIncident(@Valid @RequestBody IncidentDto.CreateRequest req,
                                                   Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        UUID tenantId = UUID.fromString(req.tenantId());
        // tamperedEntryId 는 caller 가 보내지 않는다 — 서비스가 위변조 재검증 결과에서 도출한다.
        SecurityIncident created = incidents.create(tenantId, actorId, auth.getName());
        return toView(created, buildLookups(List.of(created)));
    }

    @PostMapping("/incidents/{id}/resolve")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public IncidentDto.IncidentView resolveIncident(@PathVariable String id,
                                                    @Valid @RequestBody IncidentDto.ResolveRequest req,
                                                    Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        SecurityIncident resolved = incidents.resolve(UUID.fromString(id), req.note(), actorId, auth.getName());
        return toView(resolved, buildLookups(List.of(resolved)));
    }

    @ExceptionHandler(IncidentConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> onConflict(IncidentConflictException e) {
        return Map.of("error", "conflict", "message", e.getMessage());
    }

    @ExceptionHandler(IncidentNotTamperedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> onNotTampered(IncidentNotTamperedException e) {
        return Map.of("error", "not_tampered", "message", e.getMessage());
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> onNotFound(IncidentNotFoundException e) {
        return Map.of("error", "not_found", "message", e.getMessage());
    }

    /** toView 가 N+1 없이 조회하도록 미리 배치 로드한 tenant displayName / admin email 맵. */
    private record ViewLookups(Map<UUID, String> tenantNames, Map<UUID, String> emails) {}

    private ViewLookups buildLookups(List<SecurityIncident> list) {
        Set<UUID> tenantIds = list.stream()
                .map(SecurityIncident::getTenantId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> adminIds = new HashSet<>();
        for (SecurityIncident i : list) {
            if (i.getCreatedBy() != null) adminIds.add(i.getCreatedBy());
            if (i.getResolvedBy() != null) adminIds.add(i.getResolvedBy());
        }
        Map<UUID, String> tenantNames = new HashMap<>();
        tenantRepo.findAllById(tenantIds).forEach(t -> tenantNames.put(t.getId(), t.getDisplayName()));
        Map<UUID, String> emails = new HashMap<>();
        admins.findAllById(adminIds).forEach(a -> emails.put(a.getId(), a.getEmail()));
        return new ViewLookups(tenantNames, emails);
    }

    private IncidentDto.IncidentView toView(SecurityIncident i, ViewLookups lookups) {
        String tenantName = lookups.tenantNames().getOrDefault(i.getTenantId(), i.getTenantId().toString());
        String createdByEmail = lookups.emails().getOrDefault(i.getCreatedBy(), "—");
        String resolvedByEmail = i.getResolvedBy() == null ? null
                : lookups.emails().getOrDefault(i.getResolvedBy(), "—");
        return new IncidentDto.IncidentView(
                i.getId().toString(), i.getTenantId().toString(), tenantName,
                i.getTamperedEntryId() == null ? null : i.getTamperedEntryId().toString(),
                i.getType(), i.getSeverity(), i.getStatus(), i.getDetail(),
                i.getCreatedAt().toString(), createdByEmail,
                i.getResolvedAt() == null ? null : i.getResolvedAt().toString(),
                resolvedByEmail, i.getResolutionNote());
    }
}
