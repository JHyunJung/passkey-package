package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/audit")
public class AuditLogController {

    private final AuditLogRepository repo;
    private final AuditChainVerifier verifier;

    public AuditLogController(AuditLogRepository repo, AuditChainVerifier verifier) {
        this.repo = repo;
        this.verifier = verifier;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    public ApiResponse<List<AuditLogView>> list(@RequestParam(required = false) String action,
                                                @RequestParam(required = false) UUID actorId,
                                                @RequestParam(required = false) Instant from,
                                                @RequestParam(required = false) Instant to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        Page<AuditLog> p = repo.search(action, actorId, from, to,
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
            String targetType, String targetId, String payload, Instant createdAt) {
        public static AuditLogView from(AuditLog a) {
            return new AuditLogView(
                    a.getId(), a.getActorId(), a.getActorEmail(), a.getAction(),
                    a.getTargetType(), a.getTargetId(), a.getPayload(), a.getCreatedAt());
        }
    }
}
