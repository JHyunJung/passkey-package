package com.crosscert.passkey.admin.insights

import com.crosscert.passkey.core.repository.TenantRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Clock
import java.util.UUID
import java.util.function.Function

/** "확인이 필요한 테넌트" 판정 기준(기본값: 5%p·30건, 위험 1건, 1개뿐 50%·10명). */
class PlatformInsightsAlertsTest {

    private val service = PlatformInsightsService(
        mock<EntityManager>(), mock<TenantRepository>(), Clock.systemUTC(), 5.0, 30, 1, 0.5, 10,
    )

    companion object {
        @JvmStatic
        fun row(name: String, recentA: Long, recentS: Long, priorA: Long, priorS: Long, recentRisk: Long, users: Long, single: Long): PlatformInsightsDto.TenantRow =
            PlatformInsightsDto.TenantRow(
                UUID.randomUUID(), name, name, "active", 0, 0, 0, 0, 0, 0, 0, users, single,
                recentA, recentS, priorA, priorS, recentRisk,
            )
    }

    @Test
    fun rateDropNeedsBothDropAndMinimumAttempts() {
        // 95% → 85% (10%p), 시도 100 → 하락
        assertThat(service.alerts(listOf(row("a", 100, 85, 100, 95, 0, 0, 0)))).extracting(Function { x: PlatformInsightsDto.Alert -> x.kind }).containsExactly("RATE_DROP")
        assertThat(service.alerts(listOf(row("a", 100, 85, 100, 95, 0, 0, 0)))[0].message).isEqualTo("인증 성공률 95.0% → 85.0% (10.0%p 하락, 최근 7일)")
        // 정확히 5%p 도 포함
        assertThat(service.alerts(listOf(row("a", 100, 90, 100, 95, 0, 0, 0)))).hasSize(1)
        // 4%p 는 아님, 시도 29건(작은 테넌트)도 아님, 직전 7일 시도 0 도 아님
        assertThat(service.alerts(listOf(row("a", 100, 91, 100, 95, 0, 0, 0)))).isEmpty()
        assertThat(service.alerts(listOf(row("a", 29, 10, 100, 95, 0, 0, 0)))).isEmpty()
        assertThat(service.alerts(listOf(row("a", 100, 10, 0, 0, 0, 0, 0)))).isEmpty()
    }

    @Test
    fun riskAndSinglePasskeyAndOrder() {
        val a = service.alerts(
            listOf(
                row("single", 0, 0, 0, 0, 0, 10, 5), // 50%, 10명 → 포함
                row("small", 0, 0, 0, 0, 0, 9, 9), // 사용자 9명 → 제외
                row("risk", 100, 80, 100, 99, 2, 0, 0), // 위험 2건 + 19%p 하락
            ),
        )
        assertThat(a).extracting(Function { x: PlatformInsightsDto.Alert -> x.kind }).containsExactly("RISK", "RATE_DROP", "SINGLE_PASSKEY")
        assertThat(a).extracting(Function { x: PlatformInsightsDto.Alert -> x.name }).containsExactly("risk", "risk", "single")
        assertThat(a[0].message).isEqualTo("최근 7일 차단된 위험 인증 2건")
        assertThat(a[2].message).isEqualTo("패스키 1개뿐인 사용자 50.0% (5명 / 10명)")
    }
}
