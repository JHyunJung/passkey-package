package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @GetMapping("/{id}")
    public ApiResponse<TenantAdminDto.TenantView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TenantAdminDto.TenantView> create(
            @Valid @RequestBody TenantAdminDto.TenantCreateRequest req,
            Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        var view = service.create(req, actorId, auth.getName());
        return ApiResponse.ok("Tenant created", view);
    }
}
