package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.repository.AdminUserRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/api/tenants")
class TenantAdminController(
    private val service: TenantAdminService,
    private val admins: AdminUserRepository,
    private val webauthnDiffService: WebauthnDiffService,
    private val lifecycle: TenantLifecycleService,
) {

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping
    fun list(): ApiResponse<List<TenantAdminDto.TenantView>> = ApiResponse.ok(service.list())

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping("/{idOrSlug}")
    fun get(@PathVariable idOrSlug: String): ApiResponse<TenantAdminDto.TenantView> =
        ApiResponse.ok(service.get(idOrSlug))

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: TenantAdminDto.TenantCreateRequest,
        auth: Authentication,
    ): ApiResponse<TenantAdminDto.TenantView> {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        val view = service.create(req, actorId, auth.name)
        return ApiResponse.ok("Tenant created", view)
    }

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @PutMapping("/{idOrSlug}")
    fun update(
        @PathVariable idOrSlug: String,
        @Valid @RequestBody req: TenantAdminDto.TenantUpdateRequest,
        auth: Authentication,
    ): ApiResponse<TenantAdminDto.TenantView> {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        return ApiResponse.ok(
            "Tenant updated",
            service.update(idOrSlug, req, actorId, auth.name),
        )
    }

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @PostMapping("/{idOrSlug}/webauthn-config/diff")
    fun diff(
        @PathVariable idOrSlug: String,
        @RequestBody proposed: WebauthnDiffRequest,
    ): WebauthnConfigDiff {
        val tenantId = service.get(idOrSlug).id
        return webauthnDiffService.diff(tenantId, proposed)
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping("/{idOrSlug}/suspend")
    fun suspend(@PathVariable idOrSlug: String, auth: Authentication): ApiResponse<Void> {
        val tenantId = service.get(idOrSlug).id
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        lifecycle.suspend(tenantId, actorId, auth.name)
        return ApiResponse.ok()
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping("/{idOrSlug}/activate")
    fun activate(@PathVariable idOrSlug: String, auth: Authentication): ApiResponse<Void> {
        val tenantId = service.get(idOrSlug).id
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        lifecycle.activate(tenantId, actorId, auth.name)
        return ApiResponse.ok()
    }
}
