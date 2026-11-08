package com.crosscert.passkey.admin.credential

import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.PageView
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/credentials")
class CredentialAdminController(
    private val service: CredentialAdminService,
    private val admins: AdminUserRepository,
) {

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping
    fun list(
        @PathVariable tenantId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) q: String?,
    ): ApiResponse<PageView<CredentialView>> = ApiResponse.ok(service.list(tenantId, page, size, q))

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping("/{credentialId}/auth-events")
    fun authEvents(
        @PathVariable tenantId: UUID,
        @PathVariable credentialId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ApiResponse<PageView<CredentialAdminDto.AuthEventView>> =
        ApiResponse.ok(service.listAuthEvents(tenantId, credentialId, page, size))

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @DeleteMapping("/{credentialId}")
    fun revoke(
        @PathVariable tenantId: UUID,
        @PathVariable credentialId: String,
        auth: Authentication,
    ): ResponseEntity<ApiResponse<Void>> {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        service.revoke(tenantId, credentialId, actorId, auth.name)
        return ResponseEntity.ok(ApiResponse.ok())
    }
}
