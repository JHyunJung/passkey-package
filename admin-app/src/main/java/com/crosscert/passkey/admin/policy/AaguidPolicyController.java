package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/aaguid-policy")
public class AaguidPolicyController {

    private final AaguidPolicyService service;
    private final AuditLogService auditService;

    public AaguidPolicyController(AaguidPolicyService service, AuditLogService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public AaguidPolicyDto.View get(@PathVariable UUID tenantId) {
        return service.get(tenantId);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public AaguidPolicyDto.View update(@PathVariable UUID tenantId,
                                       @RequestBody AaguidPolicyDto.UpdateRequest req,
                                       Authentication auth) {
        String actor = auth.getName();
        AaguidPolicyDto.View view = service.update(tenantId, req, actor);
        auditService.append(new AuditAppendRequest(
                null,                           // actorId — admin user UUID not in principal
                actor,                          // actorEmail
                "AAGUID_POLICY_UPDATED",        // action
                "tenant",                       // targetType
                tenantId.toString(),            // targetId
                tenantId,                       // tenantId
                Map.of(                         // payload
                        "mode", view.mode().name(),
                        "mdsStrict", view.mdsStrict(),
                        "entryCount", view.entries().size())
        ));
        return view;
    }
}
