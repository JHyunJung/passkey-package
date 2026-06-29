package com.crosscert.passkey.admin.operator;

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

    @GetMapping
    public List<AdminUserDto.View> list() {
        return service.list();
    }

    @PostMapping
    public AdminUserDto.InviteResponse invite(@Valid @RequestBody AdminUserDto.InviteRequest req,
                                               Authentication auth) {
        return service.invite(req, auth.getName());
    }

    @PostMapping("/{id}/suspend")
    public void suspend(@PathVariable UUID id, Authentication auth) {
        service.suspend(id, auth.getName());
    }

    @PostMapping("/{id}/activate")
    public void activate(@PathVariable UUID id) {
        service.activate(id);
    }

    @PostMapping("/{id}/invitation/resend")
    public AdminUserDto.InvitationInfo resend(@PathVariable UUID id,
                                                Authentication auth,
                                                @RequestParam String email) {
        return service.resendInvitation(id, auth.getName(), email);
    }

    @PostMapping("/{id}/tenants")
    public void addTenant(@PathVariable UUID id,
                          @Valid @RequestBody TenantRef body,
                          Authentication auth) {
        service.addTenant(id, body.tenantId(), auth.getName());
    }

    @DeleteMapping("/{id}/tenants/{tenantId}")
    public void removeTenant(@PathVariable UUID id,
                             @PathVariable UUID tenantId,
                             Authentication auth) {
        service.removeTenant(id, tenantId, auth.getName());
    }
}
