package com.crosscert.passkey.admin.insights;

import com.crosscert.passkey.core.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** "확인이 필요한 테넌트" 판정 기준(기본값: 5%p·30건, 위험 1건, 1개뿐 50%·10명). */
class PlatformInsightsAlertsTest {

    private final PlatformInsightsService service = new PlatformInsightsService(
            mock(EntityManager.class), mock(TenantRepository.class), Clock.systemUTC(), 5, 30, 1, 0.5, 10);

    static PlatformInsightsDto.TenantRow row(String name, long recentA, long recentS, long priorA, long priorS, long recentRisk, long users, long single) {
        return new PlatformInsightsDto.TenantRow(UUID.randomUUID(), name, name, "active", 0, 0, 0, 0, 0, 0, 0, users, single,
                recentA, recentS, priorA, priorS, recentRisk);
    }

    @Test
    void rateDropNeedsBothDropAndMinimumAttempts() {
        // 95% → 85% (10%p), 시도 100 → 하락
        assertThat(service.alerts(List.of(row("a", 100, 85, 100, 95, 0, 0, 0)))).extracting(PlatformInsightsDto.Alert::getKind).containsExactly("RATE_DROP");
        assertThat(service.alerts(List.of(row("a", 100, 85, 100, 95, 0, 0, 0))).get(0).getMessage()).isEqualTo("인증 성공률 95.0% → 85.0% (10.0%p 하락, 최근 7일)");
        // 정확히 5%p 도 포함
        assertThat(service.alerts(List.of(row("a", 100, 90, 100, 95, 0, 0, 0)))).hasSize(1);
        // 4%p 는 아님, 시도 29건(작은 테넌트)도 아님, 직전 7일 시도 0 도 아님
        assertThat(service.alerts(List.of(row("a", 100, 91, 100, 95, 0, 0, 0)))).isEmpty();
        assertThat(service.alerts(List.of(row("a", 29, 10, 100, 95, 0, 0, 0)))).isEmpty();
        assertThat(service.alerts(List.of(row("a", 100, 10, 0, 0, 0, 0, 0)))).isEmpty();
    }

    @Test
    void riskAndSinglePasskeyAndOrder() {
        List<PlatformInsightsDto.Alert> a = service.alerts(List.of(
                row("single", 0, 0, 0, 0, 0, 10, 5),       // 50%, 10명 → 포함
                row("small", 0, 0, 0, 0, 0, 9, 9),         // 사용자 9명 → 제외
                row("risk", 100, 80, 100, 99, 2, 0, 0)));  // 위험 2건 + 19%p 하락
        assertThat(a).extracting(PlatformInsightsDto.Alert::getKind).containsExactly("RISK", "RATE_DROP", "SINGLE_PASSKEY");
        assertThat(a).extracting(PlatformInsightsDto.Alert::getName).containsExactly("risk", "risk", "single");
        assertThat(a.get(0).getMessage()).isEqualTo("최근 7일 차단된 위험 인증 2건");
        assertThat(a.get(2).getMessage()).isEqualTo("패스키 1개뿐인 사용자 50.0% (5명 / 10명)");
    }
}
