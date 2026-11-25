package com.crosscert.passkey.admin.usage

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.YearMonth

/**
 * 사용량 정산 JSON. PLATFORM_OPERATOR 전용(전 테넌트 숫자). period("YYYY-MM") 가 없거나 형식이 틀리거나 미래 달이면 이번 달.
 */
@RestController
@RequestMapping("/admin/api/platform/usage")
class UsageSettlementController(
    private val service: UsageSettlementService,
    private val clock: Clock,
) {

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun get(@RequestParam(name = "period", required = false) period: String?): UsageDto.View {
        val current = YearMonth.now(clock)
        val month = UsagePeriods.parsePeriod(period)?.takeIf { it <= current } ?: current
        return service.view(month)
    }
}
