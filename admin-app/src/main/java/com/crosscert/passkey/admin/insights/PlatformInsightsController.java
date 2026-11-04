package com.crosscert.passkey.admin.insights;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 플랫폼 전체 현황 JSON. PLATFORM_OPERATOR 전용(전 테넌트 데이터를 읽는다). 기간은 1·7·30일. */
@RestController
@RequestMapping("/admin/api/platform/insights")
public class PlatformInsightsController {

    private final PlatformInsightsService service;

    public PlatformInsightsController(PlatformInsightsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public PlatformInsightsDto.View get(@RequestParam(name = "windowDays", defaultValue = "7") int windowDays) {
        if (windowDays != 1 && windowDays != 7 && windowDays != 30) windowDays = 7;
        return service.compute(windowDays);
    }
}
