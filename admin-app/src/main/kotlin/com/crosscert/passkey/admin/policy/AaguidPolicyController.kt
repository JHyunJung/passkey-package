package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/aaguid-policy")
class AaguidPolicyController(
    private val service: AaguidPolicyService,
    private val auditService: AuditLogService,
) {

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    fun get(@PathVariable tenantId: UUID): AaguidPolicyDto.View = service.get(tenantId)

    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    fun update(
        @PathVariable tenantId: UUID,
        @RequestBody req: AaguidPolicyDto.UpdateRequest,
        auth: Authentication,
    ): AaguidPolicyDto.View {
        val actor = auth.name
        val view = service.update(tenantId, req, actor)
        auditService.append(
            AuditAppendRequest(
                null, // actorId — admin user UUID not in principal
                actor, // actorEmail
                "AAGUID_POLICY_UPDATED", // action
                "tenant", // targetType
                tenantId.toString(), // targetId
                tenantId, // tenantId
                java.util.Map.of<String, Any>( // payload
                    // 원 Java view.mode().name() 과 동일: mode 가 null(DB 값 누락)이면 감사 기록 전에 NPE.
                    "mode", view.mode!!.name,
                    "mdsStrict", view.mdsStrict,
                    "entryCount", view.entries.size,
                ),
            ),
        )
        return view
    }
}
