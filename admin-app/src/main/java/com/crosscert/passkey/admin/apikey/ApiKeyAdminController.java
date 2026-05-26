package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/api/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyAdminService service;
    private final AdminUserRepository admins;

    public ApiKeyAdminController(ApiKeyAdminService service,
                                 AdminUserRepository admins) {
        this.service = service;
        this.admins = admins;
    }

    @GetMapping
    public ApiResponse<List<ApiKeyAdminDto.ApiKeyView>> list(
            @RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(service.list(tenantId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApiKeyAdminDto.ApiKeyCreateResponse> issue(
            @Valid @RequestBody ApiKeyAdminDto.ApiKeyCreateRequest req,
            Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        return ApiResponse.ok("API key issued", service.issue(req, actorId, auth.getName()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable Long id, Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        service.revoke(id, actorId, auth.getName());
        return ApiResponse.ok();
    }
}
