package com.crosscert.passkey.admin.auth;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 로그인 운영자 본인의 "현재 활성 RP" 조회/변경(스위처).
 * 본인 세션 한정 — 별도 role 게이트 없이 allowedTenantIds 범위로만 제한된다.
 */
@RestController
@RequestMapping("/admin/api/active-tenant")
public class ActiveTenantController {

    private final ActiveTenantResolver resolver;

    public ActiveTenantController(ActiveTenantResolver resolver) {
        this.resolver = resolver;
    }

    public record SwitchRequest(UUID tenantId) {}
    public record ActiveTenantView(UUID activeTenantId, List<UUID> allowedTenantIds) {}

    @GetMapping
    public ActiveTenantView current() {
        AdminUserDetails me = principal();
        return new ActiveTenantView(resolver.resolve(me), List.copyOf(me.getAllowedTenantIds()));
    }

    @PostMapping
    public ActiveTenantView switchTenant(@RequestBody SwitchRequest req) {
        AdminUserDetails me = principal();
        resolver.setActive(me, req.tenantId());   // 허용범위 밖이면 ACCESS_DENIED
        return new ActiveTenantView(resolver.resolve(me), List.copyOf(me.getAllowedTenantIds()));
    }

    private AdminUserDetails principal() {
        return (AdminUserDetails) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }
}
