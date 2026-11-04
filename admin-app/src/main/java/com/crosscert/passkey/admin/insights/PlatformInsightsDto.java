package com.crosscert.passkey.admin.insights;

import com.crosscert.passkey.admin.funnel.AdoptionDto;
import com.crosscert.passkey.admin.funnel.FunnelDto;

import java.util.List;
import java.util.UUID;

/** 플랫폼 전체 현황(PLATFORM_OPERATOR 전용). 테넌트 인증 현황 탭과 같은 정의를 전 테넌트에 대해 한 번에 집계한다. */
public final class PlatformInsightsDto {
    private PlatformInsightsDto() {}

    public record View(
            int windowDays,
            Totals totals,
            List<TenantRow> tenants,                  // 활성 사용자 많은 순
            List<Alert> alerts,                       // 확인이 필요한 테넌트(기준: thresholds)
            List<FunnelDto.DailyPoint> authSeries,    // 플랫폼 일별 인증 시도/성공(오래된 날 → 오늘)
            List<AdoptionDto.Month> months,           // 최근 3개월(인증 기록 90일 보관)
            List<AdoptionDto.Device> devices,         // 전체 인증기 분포
            Thresholds thresholds
    ) {}

    public record Totals(long tenants, long activeTenants, long activeUsers,
                         long authAttempts, long authSuccess, long regAttempts, long regSuccess,
                         long risk, long credentials, long users, long singlePasskeyUsers) {}

    /** 기간 지표 + 확인 필요 판정용 최근 7일·그 앞 7일 인증 시도/성공과 최근 7일 위험 인증. */
    public record TenantRow(UUID tenantId, String name, String slug, String status,
                            long activeUsers, long authAttempts, long authSuccess, long regAttempts, long regSuccess,
                            long risk, long credentials, long users, long singlePasskeyUsers,
                            long recentAuthAttempts, long recentAuthSuccess, long priorAuthAttempts, long priorAuthSuccess,
                            long recentRisk) {}

    /** kind: RATE_DROP(성공률 하락) · RISK(위험 인증) · SINGLE_PASSKEY(패스키 1개뿐 비율). */
    public record Alert(UUID tenantId, String name, String slug, String kind, String message) {}

    public record Thresholds(double rateDropPp, long rateDropMinAttempts, long riskMin, double singleRatio, long singleMinUsers) {}
}
