package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.web.format.PolicyValidation
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/api/security-policy")
class SecurityPolicyController(
    private val service: SecurityPolicyService,
    private val auditService: AuditLogService,
) {

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun get(): SecurityPolicyDto.View = service.get()

    @PutMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun update(
        @Valid @RequestBody req: SecurityPolicyDto.UpdateRequest,
        auth: Authentication,
    ): SecurityPolicyDto.View {
        // 화면(SecurityPolicyTabController)과 같은 origin 규칙 — https://호스트[:포트] 만(경로·와일드카드 불가).
        // Bean Validation 은 공백·길이만 보므로 "*"·경로 포함 값이 저장돼 allowCredentials CORS 를 깨뜨렸다.
        PolicyValidation.validate(req)["corsAllowlist"]?.let { throw IllegalArgumentException(it) }
        val actor = auth.name
        val view = service.update(req, actor)
        auditService.append(
            AuditAppendRequest(
                null,
                actor,
                "SECURITY_POLICY_UPDATED",
                "security_policy",
                "1",
                null,
                java.util.Map.of<String, Any>(
                    "sessionIdleTimeoutMinutes", view.sessionIdleTimeoutMinutes,
                    "mfaRequired", view.mfaRequired,
                    "corsAllowlistSize", view.corsAllowlist.size,
                ),
            ),
        )
        return view
    }
}
