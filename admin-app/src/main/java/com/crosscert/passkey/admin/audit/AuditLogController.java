package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/audit")
public class AuditLogController {

    private final AuditLogRepository repo;
    private final AuditChainVerifier verifier;
    private final TenantBoundary tenantBoundary;

    public AuditLogController(AuditLogRepository repo,
                              AuditChainVerifier verifier,
                              TenantBoundary tenantBoundary) {
        this.repo = repo;
        this.verifier = verifier;
        this.tenantBoundary = tenantBoundary;
    }

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping
    public ApiResponse<List<AuditLogView>> list(@RequestParam(required = false) String action,
                                                @RequestParam(required = false) UUID actorId,
                                                @RequestParam(required = false) UUID tenantId,
                                                @RequestParam(required = false) Instant from,
                                                @RequestParam(required = false) Instant to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        Optional<UUID> scope = tenantBoundary.currentTenantScope();
        UUID effectiveTenantId;
        if (scope.isPresent()) {
            // RP_ADMIN: forced to own tenant. Explicit mismatched tenantId param → 403.
            if (tenantId != null && !tenantId.equals(scope.get())) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED,
                        "RP_ADMIN cannot query audit for other tenant");
            }
            effectiveTenantId = scope.get();
        } else {
            // PLATFORM_OPERATOR: tenantId param as-is (null = all tenants).
            effectiveTenantId = tenantId;
        }
        Page<AuditLog> p = repo.search(action, actorId, effectiveTenantId, from, to,
                PageRequest.of(page, Math.min(size, 200)));
        return ApiResponse.ok(p.getContent().stream().map(AuditLogView::from).toList());
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/verify")
    public ApiResponse<AuditChainVerifier.Result> verify() {
        return ApiResponse.ok(verifier.verify());
    }

    public record AuditLogView(
            UUID id, UUID actorId, String actorEmail, String action,
            String targetType, String targetId, UUID tenantId, String payload, OffsetDateTime createdAt) {
        public static AuditLogView from(AuditLog a) {
            return new AuditLogView(
                    a.getId(), a.getActorId(), a.getActorEmail(), a.getAction(),
                    a.getTargetType(), a.getTargetId(), a.getTenantId(), a.getPayload(), a.getCreatedAt());
        }
    }
}
