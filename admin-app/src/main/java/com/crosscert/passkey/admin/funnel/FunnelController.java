package com.crosscert.passkey.admin.funnel;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/funnel")
public class FunnelController {

    private final FunnelService service;

    public FunnelController(FunnelService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public FunnelDto.View get(
            @PathVariable UUID tenantId,
            @RequestParam(name = "windowDays", defaultValue = "7") int windowDays) {
        if (windowDays != 1 && windowDays != 7 && windowDays != 30) {
            windowDays = 7;
        }
        return service.compute(tenantId, windowDays);
    }
}
