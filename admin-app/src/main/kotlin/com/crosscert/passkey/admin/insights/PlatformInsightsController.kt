package com.crosscert.passkey.admin.insights

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 플랫폼 전체 현황 JSON. PLATFORM_OPERATOR 전용(전 테넌트 데이터를 읽는다). 기간은 1·7·30일. */
@RestController
@RequestMapping("/admin/api/platform/insights")
class PlatformInsightsController(private val service: PlatformInsightsService) {

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun get(@RequestParam(name = "windowDays", defaultValue = "7") windowDays: Int): PlatformInsightsDto.View {
        val days = if (windowDays != 1 && windowDays != 7 && windowDays != 30) 7 else windowDays
        return service.compute(days)
    }
}
