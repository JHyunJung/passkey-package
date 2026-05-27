package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.PageView;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/credentials")
public class CredentialAdminController {

    private final CredentialAdminService service;
    private final AdminUserRepository admins;

    public CredentialAdminController(CredentialAdminService service, AdminUserRepository admins) {
        this.service = service;
        this.admins = admins;
    }

    @GetMapping
    public ApiResponse<PageView<CredentialView>> list(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(service.list(tenantId, page, size, q));
    }

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @DeleteMapping("/{credentialId}")
    public ResponseEntity<ApiResponse<Void>> revoke(
            @PathVariable UUID tenantId,
            @PathVariable String credentialId,
            Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        service.revoke(tenantId, credentialId, actorId, auth.getName());
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
