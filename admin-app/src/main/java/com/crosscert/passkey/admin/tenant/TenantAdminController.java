package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/tenants")
public class TenantAdminController {

    private final TenantAdminService service;
    private final AdminUserRepository admins;

    public TenantAdminController(TenantAdminService service,
                                 AdminUserRepository admins) {
        this.service = service;
        this.admins = admins;
    }

    @GetMapping
    public ApiResponse<List<TenantAdminDto.TenantView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{idOrSlug}")
    public ApiResponse<TenantAdminDto.TenantView> get(@PathVariable String idOrSlug) {
        return ApiResponse.ok(service.get(idOrSlug));
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TenantAdminDto.TenantView> create(
            @Valid @RequestBody TenantAdminDto.TenantCreateRequest req,
            Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        var view = service.create(req, actorId, auth.getName());
        return ApiResponse.ok("Tenant created", view);
    }

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @PutMapping("/{idOrSlug}")
    public ApiResponse<TenantAdminDto.TenantView> update(
            @PathVariable String idOrSlug,
            @Valid @RequestBody TenantAdminDto.TenantUpdateRequest req,
            Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        return ApiResponse.ok("Tenant updated",
                service.update(idOrSlug, req, actorId, auth.getName()));
    }
}
