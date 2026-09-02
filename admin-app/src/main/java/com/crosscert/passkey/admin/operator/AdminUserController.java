package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/admin-users")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    public record TenantRef(@NotNull UUID tenantId) {}

    /** actorId(UUID) — G04 audit trail anchor; actorEmail changes, actorId doesn't. */
    private static UUID actorId(Authentication auth) {
        return ((AdminUserDetails) auth.getPrincipal()).getId();
    }

    @GetMapping
    public List<AdminUserDto.View> list() {
        return service.list();
    }

    @PostMapping("/{id}/suspend")
    public void suspend(@PathVariable UUID id, Authentication auth) {
        service.suspend(id, actorId(auth), auth.getName());
    }

    @PostMapping("/{id}/activate")
    public void activate(@PathVariable UUID id, Authentication auth) {
        service.activate(id, actorId(auth), auth.getName());
    }

    @PostMapping("/{id}/tenants")
    public void addTenant(@PathVariable UUID id,
                          @Valid @RequestBody TenantRef body,
                          Authentication auth) {
        service.addTenant(id, body.tenantId(), actorId(auth), auth.getName());
    }

    @DeleteMapping("/{id}/tenants/{tenantId}")
    public void removeTenant(@PathVariable UUID id,
                             @PathVariable UUID tenantId,
                             Authentication auth) {
        service.removeTenant(id, tenantId, actorId(auth), auth.getName());
    }
}
