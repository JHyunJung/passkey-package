package com.crosscert.passkey.admin.funnel

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/funnel")
class FunnelController(private val service: FunnelService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    fun get(
        @PathVariable tenantId: UUID,
        @RequestParam(name = "windowDays", defaultValue = "7") windowDays: Int,
    ): FunnelDto.View {
        val days = if (windowDays != 1 && windowDays != 7 && windowDays != 30) 7 else windowDays
        return service.compute(tenantId, days)
    }
}
