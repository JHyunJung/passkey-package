package com.crosscert.passkey.admin.apikey

import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.repository.AdminUserRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/admin/api/api-keys")
class ApiKeyAdminController(
    private val service: ApiKeyAdminService,
    private val admins: AdminUserRepository,
) {

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping
    fun list(@RequestParam(required = false) tenantId: String?): ApiResponse<List<ApiKeyAdminDto.ApiKeyView>> =
        ApiResponse.ok(service.list(tenantId))

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(
        @Valid @RequestBody req: ApiKeyAdminDto.ApiKeyCreateRequest,
        auth: Authentication,
    ): ApiResponse<ApiKeyAdminDto.ApiKeyCreateResponse> {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        return ApiResponse.ok("API key issued", service.issue(req, actorId, auth.name))
    }

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @PostMapping("/{id}/rotate")
    fun rotate(@PathVariable id: UUID, auth: Authentication): ApiResponse<ApiKeyAdminDto.ApiKeyRotateResponse> {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        return ApiResponse.ok("API key rotated", service.rotate(id, actorId, auth.name))
    }

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @DeleteMapping("/{id}")
    fun revoke(@PathVariable id: UUID, auth: Authentication): ApiResponse<Void> {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        service.revoke(id, actorId, auth.name)
        return ApiResponse.ok()
    }
}
