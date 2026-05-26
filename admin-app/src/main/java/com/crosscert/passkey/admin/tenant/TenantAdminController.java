package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
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
    public List<TenantAdminDto.TenantView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public TenantAdminDto.TenantView get(@PathVariable String id) {
        return service.get(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<TenantAdminDto.TenantView> create(
            @Valid @RequestBody TenantAdminDto.TenantCreateRequest req,
            Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        var view = service.create(req, actorId, auth.getName());
        return ResponseEntity.created(URI.create("/admin/api/tenants/" + view.id())).body(view);
    }
}
