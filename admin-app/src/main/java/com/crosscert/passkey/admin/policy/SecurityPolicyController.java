package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/api/security-policy")
public class SecurityPolicyController {

    private final SecurityPolicyService service;
    private final AuditLogService auditService;

    public SecurityPolicyController(SecurityPolicyService service, AuditLogService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public SecurityPolicyDto.View get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public SecurityPolicyDto.View update(@Valid @RequestBody SecurityPolicyDto.UpdateRequest req,
                                         Authentication auth) {
        String actor = auth.getName();
        SecurityPolicyDto.View view = service.update(req, actor);
        auditService.append(new AuditAppendRequest(
                null,
                actor,
                "SECURITY_POLICY_UPDATED",
                "security_policy",
                "1",
                null,
                Map.of(
                        "sessionIdleTimeoutMinutes", view.sessionIdleTimeoutMinutes(),
                        "passwordMinLength", view.passwordMinLength(),
                        "mfaRequired", view.mfaRequired(),
                        "corsAllowlistSize", view.corsAllowlist().size())
        ));
        return view;
    }
}
