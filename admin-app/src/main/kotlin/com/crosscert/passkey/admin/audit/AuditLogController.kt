package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.repository.AuditLogRepository
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@RequestMapping("/admin/api/audit")
class AuditLogController(
    private val repo: AuditLogRepository,
    private val verifier: AuditChainVerifier,
    private val tenantBoundary: TenantBoundary,
) {

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping
    fun list(
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) actorId: UUID?,
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: OffsetDateTime?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: OffsetDateTime?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ApiResponse<List<AuditLogView>> {
        val scope = tenantBoundary.currentTenantScope()
        val effectiveTenantId: UUID?
        if (scope.isPresent) {
            // RP_ADMIN: forced to own tenant. Explicit mismatched tenantId param → 403.
            if (tenantId != null && tenantId != scope.get()) {
                throw BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot query audit for other tenant")
            }
            effectiveTenantId = scope.get()
        } else {
            // PLATFORM_OPERATOR: tenantId param as-is (null = all tenants).
            effectiveTenantId = tenantId
        }
        val p = repo.search(action, actorId, effectiveTenantId, from, to, PageRequest.of(page, Math.min(size, 200)))
        return ApiResponse.ok(p.content.map { AuditLogView.from(it) })
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/verify")
    fun verify(): ApiResponse<AuditChainVerifier.Result> = ApiResponse.ok(verifier.verify())

    data class AuditLogView(
        val id: UUID?,
        val actorId: UUID?,
        val actorEmail: String?,
        val action: String?,
        val targetType: String?,
        val targetId: String?,
        val tenantId: UUID?,
        val payload: String?,
        val createdAt: OffsetDateTime?,
    ) {
        companion object {
            @JvmStatic
            fun from(a: AuditLog): AuditLogView = AuditLogView(
                a.id, a.actorId, a.actorEmail, a.action,
                a.targetType, a.targetId, a.tenantId, a.payload, a.createdAt,
            )
        }
    }
}
