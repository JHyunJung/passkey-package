package com.crosscert.passkey.admin.funnel;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 패스키 도입·사용 지표 JSON(인증 현황 탭과 같은 데이터). 기간은 funnel 과 같은 1·7·30일. */
@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/adoption")
public class AdoptionController {

    private final AdoptionService service;

    public AdoptionController(AdoptionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public AdoptionDto.View get(@PathVariable UUID tenantId,
                                @RequestParam(name = "windowDays", defaultValue = "7") int windowDays) {
        if (windowDays != 1 && windowDays != 7 && windowDays != 30) windowDays = 7;
        return service.compute(tenantId, windowDays);
    }
}
